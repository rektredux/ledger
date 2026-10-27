#!/usr/bin/env bash
# Development Maven wrapper for sandboxed/proxied environments.
#
# You do NOT need this script for normal use. If `mvn -v` works and you have
# direct internet access, just run `mvn` from the backend/ directory.
#
# This wrapper exists because some development sandboxes:
#   1. do not put a JDK on PATH / do not set JAVA_HOME,
#   2. make ~/.m2/repository read-only, so Maven cannot cache downloads there,
#   3. only allow network egress through an authenticated HTTP proxy, which
#      Maven (unlike npm/curl) does not pick up from HTTP_PROXY env vars.
#
# It solves all three without writing anything into your home directory and
# without committing proxy credentials: the settings.xml is generated fresh on
# every invocation into a gitignored build directory.
#
# Usage: scripts/dev-mvn.sh <any maven args>     e.g. scripts/dev-mvn.sh test
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# --- 1. Locate a JDK 21+ -------------------------------------------------------
if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME:-}/bin/javac" ]]; then
  for candidate in \
    /Library/Java/JavaVirtualMachines/microsoft-21.jdk/Contents/Home \
    /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
    /Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home \
    /opt/homebrew/opt/openjdk@21 \
    /opt/homebrew/opt/openjdk; do
    if [[ -x "$candidate/bin/javac" ]]; then JAVA_HOME="$candidate"; break; fi
  done
fi
if [[ -z "${JAVA_HOME:-}" ]] && command -v /usr/libexec/java_home >/dev/null 2>&1; then
  JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
fi
if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/javac" ]]; then
  echo "dev-mvn: could not locate a JDK 21 installation. Set JAVA_HOME and retry." >&2
  exit 1
fi
export JAVA_HOME

# --- 2. Locate Maven ----------------------------------------------------------
MVN_BIN="${MVN_BIN:-}"
if [[ -z "$MVN_BIN" ]]; then
  if command -v mvn >/dev/null 2>&1; then
    MVN_BIN="$(command -v mvn)"
  else
    for candidate in /Applications/apache-maven-*/bin/mvn /opt/homebrew/bin/mvn; do
      [[ -x "$candidate" ]] && MVN_BIN="$candidate" && break
    done
  fi
fi
if [[ -z "$MVN_BIN" || ! -x "$MVN_BIN" ]]; then
  echo "dev-mvn: could not locate the 'mvn' executable. Install Maven or set MVN_BIN." >&2
  exit 1
fi

# --- 3. Writable local repository, reading through to the home cache ----------
LOCAL_REPO="${LEDGER_MVN_REPO:-$ROOT/backend/.mvn-repo}"
HOME_CACHE="${HOME}/.m2/repository"
mkdir -p "$LOCAL_REPO"

GEN_DIR="$ROOT/backend/target/dev-mvn"
mkdir -p "$GEN_DIR"
SETTINGS="$GEN_DIR/settings.xml"

# --- 4. Parse proxy credentials from the environment (never written to git) ---
# urldecode %XX escapes; proxy usernames are commonly base64 with '=' encoded.
urldecode() { printf '%b' "${1//%/\\x}"; }

proxy_xml=""
raw_proxy="${HTTPS_PROXY:-${https_proxy:-${HTTP_PROXY:-${http_proxy:-}}}}"
if [[ -n "$raw_proxy" ]]; then
  stripped="${raw_proxy#*://}"                     # drop scheme
  if [[ "$stripped" == *"@"* ]]; then
    creds="${stripped%@*}"; hostport="${stripped##*@}"
    p_user="$(urldecode "${creds%%:*}")"
    p_pass="$(urldecode "${creds#*:}")"
  else
    creds=""; hostport="$stripped"; p_user=""; p_pass=""
  fi
  hostport="${hostport%%/*}"
  p_host="${hostport%%:*}"
  p_port="${hostport##*:}"
  [[ "$p_port" == "$p_host" ]] && p_port=80

  auth_xml=""
  if [[ -n "$p_user" ]]; then
    auth_xml="      <username><![CDATA[${p_user}]]></username>
      <password><![CDATA[${p_pass}]]></password>"
  fi
  # NO_PROXY must be semicolon-separated with wildcards for Maven.
  non_proxy="localhost|127.0.0.1|[::1]"
  proxy_xml="  <proxies>
    <proxy>
      <id>sandbox-https</id><active>true</active>
      <protocol>https</protocol>
      <host>${p_host}</host><port>${p_port}</port>
${auth_xml}
      <nonProxyHosts>${non_proxy}</nonProxyHosts>
    </proxy>
    <proxy>
      <id>sandbox-http</id><active>true</active>
      <protocol>http</protocol>
      <host>${p_host}</host><port>${p_port}</port>
${auth_xml}
      <nonProxyHosts>${non_proxy}</nonProxyHosts>
    </proxy>
  </proxies>"
fi

# The home cache is exposed as a read-only file:// remote repository so the
# 2.7 GB of already-downloaded artifacts are reused instead of re-fetched.
home_cache_repo=""
if [[ -d "$HOME_CACHE" ]]; then
  home_cache_repo="      <repository>
        <id>home-cache</id><url>file://${HOME_CACHE}</url>
        <releases><enabled>true</enabled><updatePolicy>never</updatePolicy></releases>
        <snapshots><enabled>false</enabled></snapshots>
      </repository>"
  home_cache_plugin_repo="      <pluginRepository>
        <id>home-cache-plugins</id><url>file://${HOME_CACHE}</url>
        <releases><enabled>true</enabled><updatePolicy>never</updatePolicy></releases>
        <snapshots><enabled>false</enabled></snapshots>
      </pluginRepository>"
fi

cat >"$SETTINGS" <<XML
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <localRepository>${LOCAL_REPO}</localRepository>
  <interactiveMode>false</interactiveMode>
${proxy_xml}
  <profiles>
    <profile>
      <id>ledger-dev</id>
      <repositories>
${home_cache_repo}
      <repository>
        <id>maven-central-anon</id><url>https://repo.maven.apache.org/maven2</url>
        <releases><enabled>true</enabled></releases>
        <snapshots><enabled>false</enabled></snapshots>
      </repository>
      </repositories>
      <pluginRepositories>
${home_cache_plugin_repo:-}
      <pluginRepository>
        <id>maven-central-anon-plugins</id><url>https://repo.maven.apache.org/maven2</url>
        <releases><enabled>true</enabled></releases>
        <snapshots><enabled>false</enabled></snapshots>
      </pluginRepository>
      </pluginRepositories>
    </profile>
  </profiles>
  <activeProfiles><activeProfile>ledger-dev</activeProfile></activeProfiles>
</settings>
XML

chmod 600 "$SETTINGS"

# Maven's *global* settings (${maven.home}/conf/settings.xml) may attach
# corporate repository credentials to the server id "central". Those creds make
# anonymous Maven Central requests fail, so the build is pointed at an empty
# global settings file and uses its own repository id instead of "central".
EMPTY_GLOBAL="$GEN_DIR/global-settings.xml"
cat >"$EMPTY_GLOBAL" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"/>
XML

# Some sandboxes deny writes to the OS temp directory that the JVM picks by
# default on macOS (/var/folders/...). That breaks jansi's native library
# unpacking and, more importantly, Surefire's fork temp directory. Point both
# the Maven JVM and the forked test JVMs (see surefire argLine in pom.xml) at a
# directory inside the project, which is always writable.
BUILD_TMP="$ROOT/backend/target/tmp"
mkdir -p "$BUILD_TMP"
export MAVEN_OPTS="${MAVEN_OPTS:-} -Djansi.force=false -Dstyle.color=never -Djava.io.tmpdir=$BUILD_TMP"

exec "$MVN_BIN" -B -s "$SETTINGS" -gs "$EMPTY_GLOBAL" -f "$ROOT/backend/pom.xml" "$@"
