-- Seed data: the default category tree and the built-in categorization rules.
--
-- System categories use readable slug ids ('food', 'food.groceries') rather
-- than UUIDs. They are stable identifiers that survive a backup/restore round
-- trip and make the rule rows below legible. User-created categories get
-- UUIDs, so the two can never collide.
--
-- `excluded_from_spending = 1` marks categories where money moving does NOT
-- constitute expenditure: transfers between owned accounts, credit card bill
-- payments, and money moved into investments. Treating these as spending is
-- the single most common way a personal finance app doubles its own numbers.

INSERT INTO category (id, name, parent_id, excluded_from_spending, system, icon, created_at) VALUES
-- Top level
('food',        'Food & Dining',     NULL, 0, 1, 'utensils',      '2024-01-01T00:00:00.000Z'),
('transport',   'Transport',         NULL, 0, 1, 'car',           '2024-01-01T00:00:00.000Z'),
('shopping',    'Shopping',          NULL, 0, 1, 'shopping-bag',  '2024-01-01T00:00:00.000Z'),
('bills',       'Bills & Utilities', NULL, 0, 1, 'receipt',       '2024-01-01T00:00:00.000Z'),
('entertain',   'Entertainment',     NULL, 0, 1, 'clapperboard',  '2024-01-01T00:00:00.000Z'),
('health',      'Health',            NULL, 0, 1, 'heart-pulse',   '2024-01-01T00:00:00.000Z'),
('housing',     'Housing',           NULL, 0, 1, 'home',          '2024-01-01T00:00:00.000Z'),
('education',   'Education',         NULL, 0, 1, 'graduation-cap','2024-01-01T00:00:00.000Z'),
('travel',      'Travel',            NULL, 0, 1, 'plane',         '2024-01-01T00:00:00.000Z'),
('personal',    'Personal Care',     NULL, 0, 1, 'scissors',      '2024-01-01T00:00:00.000Z'),
('financial',   'Fees & Financial',  NULL, 0, 1, 'landmark',      '2024-01-01T00:00:00.000Z'),
('income',      'Income',            NULL, 0, 1, 'trending-up',   '2024-01-01T00:00:00.000Z'),
('transfer',    'Transfers',         NULL, 1, 1, 'arrow-left-right','2024-01-01T00:00:00.000Z'),
('investment',  'Investments',       NULL, 1, 1, 'piggy-bank',    '2024-01-01T00:00:00.000Z'),
('uncategorized','Uncategorized',    NULL, 0, 1, 'circle-help',   '2024-01-01T00:00:00.000Z');

INSERT INTO category (id, name, parent_id, excluded_from_spending, system, icon, created_at) VALUES
-- Food & Dining
('food.groceries',  'Groceries',        'food', 0, 1, NULL, '2024-01-01T00:00:00.000Z'),
('food.delivery',   'Food Delivery',    'food', 0, 1, NULL, '2024-01-01T00:00:00.000Z'),
('food.restaurant', 'Restaurants',      'food', 0, 1, NULL, '2024-01-01T00:00:00.000Z'),
('food.cafe',       'Cafes & Snacks',   'food', 0, 1, NULL, '2024-01-01T00:00:00.000Z'),
-- Transport
('transport.fuel',  'Fuel',             'transport', 0, 1, NULL, '2024-01-01T00:00:00.000Z'),
('transport.cab',   'Cabs & Ride-hailing','transport', 0, 1, NULL, '2024-01-01T00:00:00.000Z'),
('transport.public','Public Transport', 'transport', 0, 1, NULL, '2024-01-01T00:00:00.000Z'),
('transport.toll',  'Tolls & Parking',  'transport', 0, 1, NULL, '2024-01-01T00:00:00.000Z'),
-- Shopping
('shopping.online', 'Online Marketplace','shopping', 0, 1, NULL, '2024-01-01T00:00:00.000Z'),
('shopping.clothing','Clothing',        'shopping', 0, 1, NULL, '2024-01-01T00:00:00.000Z'),
('shopping.electronics','Electronics',  'shopping', 0, 1, NULL, '2024-01-01T00:00:00.000Z'),
('shopping.home',   'Home & Furniture', 'shopping', 0, 1, NULL, '2024-01-01T00:00:00.000Z'),
-- Bills & Utilities
('bills.electricity','Electricity',     'bills', 0, 1, NULL, '2024-01-01T00:00:00.000Z'),
('bills.internet',  'Internet & Broadband','bills', 0, 1, NULL, '2024-01-01T00:00:00.000Z'),
('bills.mobile',    'Mobile & DTH',     'bills', 0, 1, NULL, '2024-01-01T00:00:00.000Z'),
('bills.gas',       'Gas & Water',      'bills', 0, 1, NULL, '2024-01-01T00:00:00.000Z'),
-- Entertainment
('entertain.streaming','Streaming',     'entertain', 0, 1, NULL, '2024-01-01T00:00:00.000Z'),
('entertain.events','Movies & Events',  'entertain', 0, 1, NULL, '2024-01-01T00:00:00.000Z'),
-- Health
('health.pharmacy', 'Pharmacy',         'health', 0, 1, NULL, '2024-01-01T00:00:00.000Z'),
('health.clinic',   'Doctor & Diagnostics','health', 0, 1, NULL, '2024-01-01T00:00:00.000Z'),
('health.insurance','Health Insurance', 'health', 0, 1, NULL, '2024-01-01T00:00:00.000Z'),
-- Housing
('housing.rent',    'Rent',             'housing', 0, 1, NULL, '2024-01-01T00:00:00.000Z'),
('housing.maintenance','Maintenance',   'housing', 0, 1, NULL, '2024-01-01T00:00:00.000Z'),
-- Travel
('travel.flight',   'Flights',          'travel', 0, 1, NULL, '2024-01-01T00:00:00.000Z'),
('travel.rail',     'Trains & Bus',     'travel', 0, 1, NULL, '2024-01-01T00:00:00.000Z'),
('travel.stay',     'Hotels & Stays',   'travel', 0, 1, NULL, '2024-01-01T00:00:00.000Z'),
-- Personal care
('personal.salon',  'Salon & Grooming', 'personal', 0, 1, NULL, '2024-01-01T00:00:00.000Z'),
('personal.fitness','Fitness',          'personal', 0, 1, NULL, '2024-01-01T00:00:00.000Z'),
-- Fees & financial
('financial.fees',  'Bank Fees & Charges','financial', 0, 1, NULL, '2024-01-01T00:00:00.000Z'),
('financial.interest','Interest Charged','financial', 0, 1, NULL, '2024-01-01T00:00:00.000Z'),
('financial.tax',   'Taxes',            'financial', 0, 1, NULL, '2024-01-01T00:00:00.000Z'),
('financial.insurance','Insurance Premium','financial', 0, 1, NULL, '2024-01-01T00:00:00.000Z'),
-- Income
('income.salary',   'Salary',           'income', 0, 1, NULL, '2024-01-01T00:00:00.000Z'),
('income.interest', 'Interest & Dividend','income', 0, 1, NULL, '2024-01-01T00:00:00.000Z'),
('income.cashback', 'Cashback & Rewards','income', 0, 1, NULL, '2024-01-01T00:00:00.000Z'),
('income.other',    'Other Income',     'income', 0, 1, NULL, '2024-01-01T00:00:00.000Z'),
-- Transfers (never spending)
('transfer.self',   'Account Transfer',     'transfer', 1, 1, NULL, '2024-01-01T00:00:00.000Z'),
('transfer.cardbill','Credit Card Payment', 'transfer', 1, 1, NULL, '2024-01-01T00:00:00.000Z'),
('transfer.cash',   'Cash Withdrawal',      'transfer', 1, 1, NULL, '2024-01-01T00:00:00.000Z'),
-- Investments (not consumption)
('investment.sip',  'Mutual Funds & SIP',   'investment', 1, 1, NULL, '2024-01-01T00:00:00.000Z'),
('investment.other','Other Investments',    'investment', 1, 1, NULL, '2024-01-01T00:00:00.000Z');

-- ---------------------------------------------------------------------------
-- Merchant name normalization
--
-- Payment gateway and aggregator noise that surrounds the real merchant name in
-- bank alerts. These patterns are stripped/rewritten by MerchantNormalizer
-- before categorization runs.
-- ---------------------------------------------------------------------------

INSERT INTO merchant_alias (id, pattern, match_type, canonical_name, created_at) VALUES
('ma-swiggy',    'swiggy',        'CONTAINS', 'Swiggy',            '2024-01-01T00:00:00.000Z'),
('ma-zomato',    'zomato',        'CONTAINS', 'Zomato',            '2024-01-01T00:00:00.000Z'),
('ma-blinkit',   'blinkit',       'CONTAINS', 'Blinkit',           '2024-01-01T00:00:00.000Z'),
('ma-grofers',   'grofers',       'CONTAINS', 'Blinkit',           '2024-01-01T00:00:00.000Z'),
('ma-zepto',     'zepto',         'CONTAINS', 'Zepto',             '2024-01-01T00:00:00.000Z'),
('ma-instamart', 'instamart',     'CONTAINS', 'Swiggy Instamart',  '2024-01-01T00:00:00.000Z'),
('ma-bigbasket', 'bigbasket',     'CONTAINS', 'BigBasket',         '2024-01-01T00:00:00.000Z'),
('ma-bbdaily',   'bbdaily',       'CONTAINS', 'BigBasket',         '2024-01-01T00:00:00.000Z'),
('ma-dmart',     'dmart',         'CONTAINS', 'DMart',             '2024-01-01T00:00:00.000Z'),
('ma-amazon',    'amazon',        'CONTAINS', 'Amazon',            '2024-01-01T00:00:00.000Z'),
('ma-amzn',      'amzn',          'CONTAINS', 'Amazon',            '2024-01-01T00:00:00.000Z'),
('ma-flipkart',  'flipkart',      'CONTAINS', 'Flipkart',          '2024-01-01T00:00:00.000Z'),
('ma-myntra',    'myntra',        'CONTAINS', 'Myntra',            '2024-01-01T00:00:00.000Z'),
('ma-ajio',      'ajio',          'CONTAINS', 'AJIO',              '2024-01-01T00:00:00.000Z'),
('ma-uber',      'uber',          'CONTAINS', 'Uber',              '2024-01-01T00:00:00.000Z'),
('ma-olacabs',   'olacabs',       'CONTAINS', 'Ola',               '2024-01-01T00:00:00.000Z'),
('ma-rapido',    'rapido',        'CONTAINS', 'Rapido',            '2024-01-01T00:00:00.000Z'),
('ma-irctc',     'irctc',         'CONTAINS', 'IRCTC',             '2024-01-01T00:00:00.000Z'),
('ma-indigo',    'goindigo',      'CONTAINS', 'IndiGo',            '2024-01-01T00:00:00.000Z'),
('ma-mmt',       'makemytrip',    'CONTAINS', 'MakeMyTrip',        '2024-01-01T00:00:00.000Z'),
('ma-bms',       'bookmyshow',    'CONTAINS', 'BookMyShow',        '2024-01-01T00:00:00.000Z'),
('ma-netflix',   'netflix',       'CONTAINS', 'Netflix',           '2024-01-01T00:00:00.000Z'),
('ma-spotify',   'spotify',       'CONTAINS', 'Spotify',           '2024-01-01T00:00:00.000Z'),
('ma-hotstar',   'hotstar',       'CONTAINS', 'JioHotstar',        '2024-01-01T00:00:00.000Z'),
('ma-jio',       'reliance jio',  'CONTAINS', 'Jio',               '2024-01-01T00:00:00.000Z'),
('ma-airtel',    'airtel',        'CONTAINS', 'Airtel',            '2024-01-01T00:00:00.000Z'),
('ma-apollo',    'apollo pharmac','CONTAINS', 'Apollo Pharmacy',   '2024-01-01T00:00:00.000Z'),
('ma-pharmeasy', 'pharmeasy',     'CONTAINS', 'PharmEasy',         '2024-01-01T00:00:00.000Z'),
('ma-1mg',       'tata 1mg',      'CONTAINS', 'Tata 1mg',          '2024-01-01T00:00:00.000Z'),
('ma-cultfit',   'cult.fit',      'CONTAINS', 'Cult.fit',          '2024-01-01T00:00:00.000Z'),
('ma-urbanco',   'urban company', 'CONTAINS', 'Urban Company',     '2024-01-01T00:00:00.000Z'),
('ma-dominos',   'dominos',       'CONTAINS', 'Dominos',           '2024-01-01T00:00:00.000Z'),
('ma-starbucks', 'starbucks',     'CONTAINS', 'Starbucks',         '2024-01-01T00:00:00.000Z'),
('ma-iocl',      'indian oil',    'CONTAINS', 'Indian Oil',        '2024-01-01T00:00:00.000Z'),
('ma-hpcl',      'hindustan petro','CONTAINS','HP Petrol',         '2024-01-01T00:00:00.000Z'),
('ma-bpcl',      'bharat petro',  'CONTAINS', 'Bharat Petroleum',  '2024-01-01T00:00:00.000Z');

-- ---------------------------------------------------------------------------
-- Built-in category rules
--
-- Priority 100 is the built-in band. Rules LEARNED from user corrections are
-- written at priority 200 and rules authored by hand in Settings at 300, so a
-- correction always outranks a shipped default.
-- ---------------------------------------------------------------------------

INSERT INTO category_rule (id, pattern, match_type, category_id, priority, confidence, origin, enabled, hit_count, created_at, updated_at) VALUES
-- Food delivery / groceries
('cr-swiggy',    'swiggy',          'CONTAINS', 'food.delivery',  100, 0.95, 'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
('cr-instamart', 'swiggy instamart','CONTAINS', 'food.groceries', 110, 0.95, 'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
('cr-zomato',    'zomato',          'CONTAINS', 'food.delivery',  100, 0.95, 'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
('cr-blinkit',   'blinkit',         'CONTAINS', 'food.groceries', 100, 0.95, 'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
('cr-zepto',     'zepto',           'CONTAINS', 'food.groceries', 100, 0.95, 'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
('cr-bigbasket', 'bigbasket',       'CONTAINS', 'food.groceries', 100, 0.95, 'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
('cr-dmart',     'dmart',           'CONTAINS', 'food.groceries', 100, 0.95, 'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
('cr-licious',   'licious',         'CONTAINS', 'food.groceries', 100, 0.90, 'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
('cr-dominos',   'dominos',         'CONTAINS', 'food.restaurant',100, 0.90, 'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
('cr-starbucks', 'starbucks',       'CONTAINS', 'food.cafe',      100, 0.90, 'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
('cr-chaayos',   'chaayos',         'CONTAINS', 'food.cafe',      100, 0.90, 'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
-- Transport
('cr-uber',      'uber',            'CONTAINS', 'transport.cab',  100, 0.90, 'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
('cr-ola',       'ola',             'EXACT',    'transport.cab',  100, 0.90, 'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
('cr-rapido',    'rapido',          'CONTAINS', 'transport.cab',  100, 0.90, 'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
('cr-iocl',      'indian oil',      'CONTAINS', 'transport.fuel', 100, 0.92, 'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
('cr-hp',        'hp petrol',       'CONTAINS', 'transport.fuel', 100, 0.92, 'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
('cr-bpcl',      'bharat petroleum','CONTAINS', 'transport.fuel', 100, 0.92, 'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
('cr-fastag',    'fastag',          'CONTAINS', 'transport.toll', 100, 0.92, 'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
('cr-metro',     'metro rail',      'CONTAINS', 'transport.public',100,0.85, 'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
-- Shopping
('cr-amazon',    'amazon',          'CONTAINS', 'shopping.online',100, 0.80, 'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
('cr-flipkart',  'flipkart',        'CONTAINS', 'shopping.online',100, 0.80, 'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
('cr-myntra',    'myntra',          'CONTAINS', 'shopping.clothing',100,0.90,'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
('cr-ajio',      'ajio',            'CONTAINS', 'shopping.clothing',100,0.90,'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
('cr-croma',     'croma',           'CONTAINS', 'shopping.electronics',100,0.90,'BUILTIN',1,0,'2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
('cr-ikea',      'ikea',            'CONTAINS', 'shopping.home',  100, 0.90, 'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
-- Bills
('cr-jio',       'jio',             'CONTAINS', 'bills.mobile',   100, 0.85, 'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
('cr-airtel',    'airtel',          'CONTAINS', 'bills.mobile',   100, 0.85, 'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
('cr-vi',        'vodafone idea',   'CONTAINS', 'bills.mobile',   100, 0.85, 'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
('cr-act',       'act fibernet',    'CONTAINS', 'bills.internet', 100, 0.92, 'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
('cr-bses',      'bses',            'CONTAINS', 'bills.electricity',100,0.92,'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
('cr-tatapower', 'tata power',      'CONTAINS', 'bills.electricity',100,0.92,'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
('cr-adanielec', 'adani electricity','CONTAINS','bills.electricity',100,0.92,'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
('cr-indane',    'indane',          'CONTAINS', 'bills.gas',      100, 0.90, 'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
-- Entertainment
('cr-netflix',   'netflix',         'CONTAINS', 'entertain.streaming',100,0.95,'BUILTIN',1,0,'2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
('cr-spotify',   'spotify',         'CONTAINS', 'entertain.streaming',100,0.95,'BUILTIN',1,0,'2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
('cr-hotstar',   'hotstar',         'CONTAINS', 'entertain.streaming',100,0.95,'BUILTIN',1,0,'2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
('cr-prime',     'prime video',     'CONTAINS', 'entertain.streaming',100,0.95,'BUILTIN',1,0,'2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
('cr-bms',       'bookmyshow',      'CONTAINS', 'entertain.events',100,0.95, 'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
-- Health
('cr-apollo',    'apollo pharmacy', 'CONTAINS', 'health.pharmacy',100, 0.95, 'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
('cr-pharmeasy', 'pharmeasy',       'CONTAINS', 'health.pharmacy',100, 0.95, 'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
('cr-1mg',       'tata 1mg',        'CONTAINS', 'health.pharmacy',100, 0.95, 'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
('cr-practo',    'practo',          'CONTAINS', 'health.clinic',  100, 0.90, 'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
-- Travel
('cr-irctc',     'irctc',           'CONTAINS', 'travel.rail',    100, 0.95, 'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
('cr-indigo',    'indigo',          'CONTAINS', 'travel.flight',  100, 0.90, 'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
('cr-mmt',       'makemytrip',      'CONTAINS', 'travel.stay',    100, 0.80, 'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
('cr-oyo',       'oyo',             'CONTAINS', 'travel.stay',    100, 0.90, 'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
-- Personal care
('cr-cultfit',   'cult.fit',        'CONTAINS', 'personal.fitness',100,0.95, 'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
('cr-urbanco',   'urban company',   'CONTAINS', 'personal.salon', 100, 0.85, 'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
-- Fees and financial
('cr-gst',       'gst',             'EXACT',    'financial.tax',  100, 0.85, 'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
('cr-lic',       'lic of india',    'CONTAINS', 'financial.insurance',100,0.90,'BUILTIN',1,0,'2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
-- Investments
('cr-zerodha',   'zerodha',         'CONTAINS', 'investment.other',100,0.90, 'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
('cr-groww',     'groww',           'CONTAINS', 'investment.other',100,0.90, 'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z'),
('cr-indclear',  'indian clearing', 'CONTAINS', 'investment.sip', 100, 0.85, 'BUILTIN', 1, 0, '2024-01-01T00:00:00.000Z', '2024-01-01T00:00:00.000Z');

-- Default overall monthly budget is intentionally NOT seeded: an invented
-- budget number would be indistinguishable from one the user set.

INSERT INTO app_setting (key, value, updated_at) VALUES
('schema.seeded',         'V2',   '2024-01-01T00:00:00.000Z'),
('display.currency',      'INR',  '2024-01-01T00:00:00.000Z'),
('display.locale',        'en-IN','2024-01-01T00:00:00.000Z');
