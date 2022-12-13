import pymysql
import names
import random
import string
from credentials import mysql_endpoint, username, password, database_name

# SQL Config Values
num_customers_to_add = 100

# Connect to testudo_bank db in local MySQL Server
connection = pymysql.connect(host=mysql_endpoint, user=username, passwd = password, db=database_name)
cursor = connection.cursor()

# Make empty Customers table
create_customer_table_sql = '''
  CREATE TABLE Customers (
    CustomerID varchar(255),
    FirstName varchar(255),
    LastName varchar(255),
    CheckingBalance int,
    SavingsBalance int,
    SavingsOverdraftBalance int,
    CheckingOverdraftBalance int,
    NumFraudReversals int,
    NumDepositsForInterest int
  );
  '''
cursor.execute(create_customer_table_sql)

# Make empty Passwords table
create_password_table_sql = '''
CREATE TABLE Passwords (
  CustomerID varchar(255),
  Password varchar(255)
);
'''
cursor.execute(create_password_table_sql)

# Make empty Checking OverdraftLogs table
create_checking_overdraftlogs_table_sql = '''
CREATE TABLE CheckingOverdraftLogs (
  CustomerID varchar(255),
  Timestamp DATETIME,
  DepositAmt int,
  OldOverBalance int,
  NewOverBalance int
);
'''
cursor.execute(create_checking_overdraftlogs_table_sql)

# Make empty Savings OverdraftLogs table
create_savings_overdraftlogs_table_sql = '''
CREATE TABLE SavingsOverdraftLogs (
  CustomerID varchar(255),
  Timestamp DATETIME,
  DepositAmt int,
  OldOverBalance int,
  NewOverBalance int
);
'''
cursor.execute(create_savings_overdraftlogs_table_sql)

# Make empty Checking TransactionHistory table
create_checking_transactionhistory_table_sql = '''
CREATE TABLE CheckingTransactionHistory (
  CustomerID varchar(255),
  Timestamp DATETIME,
  Action varchar(255) CHECK (Action IN ('Deposit', 'Withdraw', 'CheckingTransferSend', 'SavingsTransferReceive', 'TransferSend', 'TransferReceive', 'CryptoBuy', 'CryptoSell')),
  Amount int
);
'''
cursor.execute(create_checking_transactionhistory_table_sql)

# Make empty Savings TransactionHistory table
create_savings_transactionhistory_table_sql = '''
CREATE TABLE SavingsTransactionHistory (
  CustomerID varchar(255),
  Timestamp DATETIME,
  Action varchar(255) CHECK (Action IN ('Deposit', 'Withdraw', 'SavingsTransferSend', 'CheckingTransferReceive', 'TransferSend', 'TransferReceive', 'CryptoBuy', 'CryptoSell')),
  Amount int
);
'''
cursor.execute(create_savings_transactionhistory_table_sql)

# Make empty Internal Transfer table
create_internaltransferhistory_table_sql = '''
CREATE TABLE InternalTransferHistory (
  CustomerID varchar(255),
  TransferFrom varchar(255) CHECK (TransferFrom IN ('checking', 'savings')),
  TransferTo varchar(255) CHECK (TransferTo IN ('checking', 'savings')),
  Timestamp DATETIME,
  Amount int
);
'''
cursor.execute(create_internaltransferhistory_table_sql)

# Make empty Transfer table
create_transferhistory_table_sql = '''
CREATE TABLE TransferHistory (
  TransferFrom varchar(255),
  TransferTo varchar(255),
  SenderAccountType varchar(255) CHECK (SenderAccountType IN ('checking', 'savings')),
  RecipientAccountType varchar(255) CHECK (RecipientAccountType IN ('checking', 'savings')),
  Timestamp DATETIME,
  Amount int
);
'''
cursor.execute(create_transferhistory_table_sql)


# Make empty CryptoHoldings table
create_cryptoholdings_table_sql = '''
CREATE TABLE CryptoHoldings (
  CustomerID varchar(255),
  CryptoName varchar(255),
  CryptoAmount decimal(30,18)
);
'''
cursor.execute(create_cryptoholdings_table_sql)


# Make empty CryptoHistory table
create_cryptohistory_table_sql = '''
CREATE TABLE CryptoHistory (
  CustomerID varchar(255),
  Timestamp DATETIME,
  Action varchar(255) CHECK (Action IN ('Buy', 'Sell')),
  CryptoName varchar(255),
  CryptoAmount decimal(30,18)
);
'''
cursor.execute(create_cryptohistory_table_sql)



# The two sets created below are used to ensure that this
# automated, randomized process does not accidentally 
# generate and use a customer ID that already is in use

# Add all existing customer IDs in the DB to a set
get_all_ids_sql = '''SELECT CustomerID FROM Customers;'''
cursor.execute(get_all_ids_sql)
ids_in_db = set()
for id in cursor.fetchall():
  ids_in_db.add(id[0])

# a set to store all IDs that are added in this Lambda 
# (so that we don't need to run a SELECT SQL query again)
ids_just_added = set()

# add random customers
for i in range(num_customers_to_add):
  # generate random 9-digit customer ID
  customer_id = ''.join(random.choices(string.digits, k = 9))

  # don't add row if someone already has this ID (really unlikely)
  if (customer_id not in ids_in_db and customer_id not in ids_just_added):

    # generate random name, balance, and password
    customer_first_name = names.get_first_name()
    customer_last_name = names.get_last_name()
    customer_checking_balance = random.randint(100, 10000) * 100 # multiply by 100 to have a penny value of 0
    customer_savings_balance = random.randint(100, 10000) * 100 # multiply by 100 to have a penny value of 0
    customer_password = ''.join(random.choices(string.ascii_lowercase + string.ascii_uppercase + string.digits, k = 9))
    
    # add random customer ID, name, and balances to Customers table.
    # all customers start with Overdraft balance of 0
    # all customers start with a NumFraudReversals of 0
    # both the balance and overdraftbalance columns represent the total dollar amount as pennies instead of dollars.
    insert_customer_sql = '''
    INSERT INTO Customers
    VALUES  ({0},{1},{2},{3},{4},{5},{6},{7},{8});
    '''.format("'" + customer_id + "'",
                "'" + customer_first_name + "'",
                "'" + customer_last_name + "'",
                customer_checking_balance,
                customer_savings_balance,
                0,
                0,
                0,
                0)
    cursor.execute(insert_customer_sql)
    
    # add customer ID and password to Passwords table
    insert_password_sql = '''
    INSERT INTO Passwords
    VALUES  ({0},{1});
    '''.format("'" + customer_id + "'",
                "'" + customer_password + "'")
    cursor.execute(insert_password_sql)
    
    # add this customer's randomly-generated ID to the set
    # to ensure this ID is not re-used by accident.
    ids_just_added.add(customer_id)

connection.commit()
cursor.close()