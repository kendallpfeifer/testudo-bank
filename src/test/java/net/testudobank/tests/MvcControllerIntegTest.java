package net.testudobank.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.script.ScriptException;

import lombok.AllArgsConstructor;
import lombok.Builder;
import net.testudobank.CryptoPriceClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.delegate.DatabaseDelegate;
import org.testcontainers.ext.ScriptUtils;
import org.testcontainers.jdbc.JdbcDatabaseDelegate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import net.testudobank.MvcController;
import net.testudobank.User;
import net.testudobank.helpers.MvcControllerIntegTestHelpers;

@Testcontainers
@SpringBootTest
public class MvcControllerIntegTest {
  //// LITERAL CONSTANTS ////
  private static String CUSTOMER1_ID = "123456789";
  private static String CUSTOMER1_PASSWORD = "password";
  private static String CUSTOMER1_FIRST_NAME = "Foo";
  private static String CUSTOMER1_LAST_NAME = "Bar";
  public static long REASONABLE_TIMESTAMP_EPSILON_IN_SECONDS = 1L;

  private static String CUSTOMER2_ID = "987654321";
  private static String CUSTOMER2_PASSWORD = "password";
  private static String CUSTOMER2_FIRST_NAME = "Foo1";
  private static String CUSTOMER2_LAST_NAME = "Bar1";

  private static String ACCOUNT_TYPE_CHECKING = "checking";
  private static String ACCOUNT_TYPE_SAVINGS = "savings";
  
  // Spins up small MySQL DB in local Docker container
  @Container
  public static MySQLContainer db = new MySQLContainer<>("mysql:5.7.37")
    .withUsername("root")
    .withPassword("db_password")
    .withDatabaseName("testudo_bank");


  private static MvcController controller;
  private static JdbcTemplate jdbcTemplate;
  private static DatabaseDelegate dbDelegate;
  private static CryptoPriceClient cryptoPriceClient = Mockito.mock(CryptoPriceClient.class);

  @BeforeAll
  public static void init() throws SQLException {
    dbDelegate = new JdbcDatabaseDelegate(db, "");
    ScriptUtils.runInitScript(dbDelegate, "createDB.sql");
    jdbcTemplate = new JdbcTemplate(MvcControllerIntegTestHelpers.dataSource(db));
    jdbcTemplate.getDataSource().getConnection().setCatalog(db.getDatabaseName());
    controller = new MvcController(jdbcTemplate, cryptoPriceClient);
  }

  @AfterEach
  public void clearDB() throws ScriptException {
    // runInitScript() pulls all the String text from the SQL file and just calls executeDatabaseScript(),
    // so it is OK to use runInitScript() again even though we aren't initializing the DB for the first time here.
    // runInitScript() is a poorly-named function.
    ScriptUtils.runInitScript(dbDelegate, "clearDB.sql");
  }

  //// INTEGRATION TESTS ////

  /**
   * Verifies the simplest deposit case for a checking account only. 
   * The customer's CheckingBalance in the Customers table should be increased,
   * and the Deposit should be logged in the CheckingTransactionHistory table.
   * 
   * Assumes that the customer's account is in the simplest state
   * (not in overdraft, account is not frozen due to too many transaction disputes, etc.)
   * 
   * @throws SQLException
   * @throws ScriptException
   */
  @Test
  public void testSimpleDepositChecking() throws SQLException, ScriptException {
    // initialize customer1 with a balance of $123.45 (to make sure this works for non-whole dollar amounts). represented as pennies in the DB.
    double CUSTOMER1_BALANCE = 123.45;
    int CUSTOMER1_CHECKING_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_BALANCE);
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER1_ID, CUSTOMER1_PASSWORD, CUSTOMER1_FIRST_NAME, CUSTOMER1_LAST_NAME, CUSTOMER1_CHECKING_BALANCE_IN_PENNIES, 0, 0);

    // Prepare Deposit Form to Deposit $12.34 to customer 1's account.
    double CUSTOMER1_AMOUNT_TO_DEPOSIT = 12.34; // user input is in dollar amount, not pennies.
    User customer1DepositFormInputs = new User();
    customer1DepositFormInputs.setUsername(CUSTOMER1_ID);
    customer1DepositFormInputs.setPassword(CUSTOMER1_PASSWORD);
    customer1DepositFormInputs.setAmountToDeposit(CUSTOMER1_AMOUNT_TO_DEPOSIT); 

    // verify that there are no logs in TransactionHistory table before Deposit
    assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM CheckingTransactionHistory;", Integer.class));

    // store timestamp of when Deposit request is sent to verify timestamps in the TransactionHistory table later
    LocalDateTime timeWhenDepositRequestSent = MvcControllerIntegTestHelpers.fetchCurrentTimeAsLocalDateTimeNoMilliseconds();
    System.out.println("Timestamp when Deposit Request is sent: " + timeWhenDepositRequestSent);

    // send request to the Deposit Form's POST handler in MvcController
    controller.submitDeposit(ACCOUNT_TYPE_CHECKING, customer1DepositFormInputs);

    // fetch updated data from the DB
    List<Map<String,Object>> customersTableData = jdbcTemplate.queryForList("SELECT * FROM Customers;");
    List<Map<String,Object>> transactionHistoryTableData = jdbcTemplate.queryForList("SELECT * FROM CheckingTransactionHistory;");
  
    // verify that customer1's data is still the only data populated in Customers table
    assertEquals(1, customersTableData.size());
    Map<String,Object> customer1Data = customersTableData.get(0);
    assertEquals(CUSTOMER1_ID, (String)customer1Data.get("CustomerID"));

    // verify customer balance was increased by $12.34
    double CUSTOMER1_EXPECTED_FINAL_BALANCE = CUSTOMER1_BALANCE + CUSTOMER1_AMOUNT_TO_DEPOSIT;
    double CUSTOMER1_EXPECTED_FINAL_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_EXPECTED_FINAL_BALANCE);
    assertEquals(CUSTOMER1_EXPECTED_FINAL_BALANCE_IN_PENNIES, (int)customer1Data.get("CheckingBalance"));

    // verify that the Deposit is the only log in TransactionHistory table
    assertEquals(1, transactionHistoryTableData.size());
    
    // verify that the Deposit's details are accurately logged in the TransactionHistory table
    Map<String,Object> customer1TransactionLog = transactionHistoryTableData.get(0);
    int CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_AMOUNT_TO_DEPOSIT);
    MvcControllerIntegTestHelpers.checkTransactionLog(customer1TransactionLog, timeWhenDepositRequestSent, CUSTOMER1_ID, MvcController.TRANSACTION_HISTORY_DEPOSIT_ACTION, CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES);
  }

  /**
   * Verifies the simplest deposit case for a savings account only. 
   * The customer's SavingsBalance in the Customers table should be increased,
   * and the Deposit should be logged in the SavingsTransactionHistory table.
   * 
   * Assumes that the customer's account is in the simplest state
   * (not in overdraft, account is not frozen due to too many transaction disputes, etc.)
   * 
   * @throws SQLException
   * @throws ScriptException
   */
  @Test
  public void testSimpleDepositSavings() throws SQLException, ScriptException {
    // initialize customer1 with a balance of $123.45 (to make sure this works for non-whole dollar amounts). represented as pennies in the DB.
    double CUSTOMER1_BALANCE = 123.45;
    int CUSTOMER1_SAVINGS_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_BALANCE);
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER1_ID, CUSTOMER1_PASSWORD, CUSTOMER1_FIRST_NAME, CUSTOMER1_LAST_NAME, 0, CUSTOMER1_SAVINGS_BALANCE_IN_PENNIES, 0);

    // Prepare Deposit Form to Deposit $12.34 to customer 1's account.
    double CUSTOMER1_AMOUNT_TO_DEPOSIT = 12.34; // user input is in dollar amount, not pennies.
    User customer1DepositFormInputs = new User();
    customer1DepositFormInputs.setUsername(CUSTOMER1_ID);
    customer1DepositFormInputs.setPassword(CUSTOMER1_PASSWORD);
    customer1DepositFormInputs.setAmountToDeposit(CUSTOMER1_AMOUNT_TO_DEPOSIT); 

    // verify that there are no logs in TransactionHistory table before Deposit
    assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM SavingsTransactionHistory;", Integer.class));

    // store timestamp of when Deposit request is sent to verify timestamps in the TransactionHistory table later
    LocalDateTime timeWhenDepositRequestSent = MvcControllerIntegTestHelpers.fetchCurrentTimeAsLocalDateTimeNoMilliseconds();
    System.out.println("Timestamp when Deposit Request is sent: " + timeWhenDepositRequestSent);

    // send request to the Deposit Form's POST handler in MvcController
    controller.submitDeposit(ACCOUNT_TYPE_SAVINGS, customer1DepositFormInputs);

    // fetch updated data from the DB
    List<Map<String,Object>> customersTableData = jdbcTemplate.queryForList("SELECT * FROM Customers;");
    List<Map<String,Object>> transactionHistoryTableData = jdbcTemplate.queryForList("SELECT * FROM SavingsTransactionHistory;");
    // verify that customer1's data is still the only data populated in Customers table
    assertEquals(1, customersTableData.size());
    Map<String,Object> customer1Data = customersTableData.get(0);
    assertEquals(CUSTOMER1_ID, (String)customer1Data.get("CustomerID"));

    // verify customer balance was increased by $12.34
    double CUSTOMER1_EXPECTED_FINAL_BALANCE = CUSTOMER1_BALANCE + CUSTOMER1_AMOUNT_TO_DEPOSIT;
    double CUSTOMER1_EXPECTED_FINAL_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_EXPECTED_FINAL_BALANCE);
    assertEquals(CUSTOMER1_EXPECTED_FINAL_BALANCE_IN_PENNIES, (int)customer1Data.get("SavingsBalance"));

    // verify that the Deposit is the only log in TransactionHistory table
    assertEquals(1, transactionHistoryTableData.size());
    
    // verify that the Deposit's details are accurately logged in the TransactionHistory table
    Map<String,Object> customer1TransactionLog = transactionHistoryTableData.get(0);
    int CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_AMOUNT_TO_DEPOSIT);
    MvcControllerIntegTestHelpers.checkTransactionLog(customer1TransactionLog, timeWhenDepositRequestSent, CUSTOMER1_ID, MvcController.TRANSACTION_HISTORY_DEPOSIT_ACTION, CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES);
  }

  /**
   * Verifies the simplest deposit case for a checking account only, where the 
   * initial savings balance is not 0. The customer's CheckingBalance in the 
   * Customers table should be increased, and the Deposit should be logged 
   * in the CheckingTransactionHistory table. The SavingsTransactionHistory table
   * shoule be empty, and the savings balance should not change. 
   * 
   * Assumes that the customer's account is in the simplest state
   * (not in overdraft, account is not frozen due to too many transaction disputes, etc.)
   * 
   * @throws SQLException
   * @throws ScriptException
   */
  @Test
  public void testSimpleDepositCheckingSavingsNotZero() throws SQLException, ScriptException {
    // initialize customer1 with a balance of $123.45 (to make sure this works for non-whole dollar amounts). represented as pennies in the DB.
    double CUSTOMER1_BALANCE = 123.45;
    int CUSTOMER1_CHECKING_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_BALANCE);
    double CUSTOMER1_SAVINGS_BALANCE = 100;
    int CUSTOMER1_SAVINGS_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_SAVINGS_BALANCE);

    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER1_ID, CUSTOMER1_PASSWORD, CUSTOMER1_FIRST_NAME, CUSTOMER1_LAST_NAME, CUSTOMER1_CHECKING_BALANCE_IN_PENNIES, CUSTOMER1_SAVINGS_BALANCE_IN_PENNIES, 0);

    // Prepare Deposit Form to Deposit $12.34 to customer 1's account.
    double CUSTOMER1_AMOUNT_TO_DEPOSIT = 12.34; // user input is in dollar amount, not pennies.
    User customer1DepositFormInputs = new User();
    customer1DepositFormInputs.setUsername(CUSTOMER1_ID);
    customer1DepositFormInputs.setPassword(CUSTOMER1_PASSWORD);
    customer1DepositFormInputs.setAmountToDeposit(CUSTOMER1_AMOUNT_TO_DEPOSIT); 

    // verify that there are no logs in TransactionHistory table before Deposit
    assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM CheckingTransactionHistory;", Integer.class));
    assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM SavingsTransactionHistory;", Integer.class));

    // store timestamp of when Deposit request is sent to verify timestamps in the TransactionHistory table later
    LocalDateTime timeWhenDepositRequestSent = MvcControllerIntegTestHelpers.fetchCurrentTimeAsLocalDateTimeNoMilliseconds();
    System.out.println("Timestamp when Deposit Request is sent: " + timeWhenDepositRequestSent);

    // send request to the Deposit Form's POST handler in MvcController
    controller.submitDeposit(ACCOUNT_TYPE_CHECKING, customer1DepositFormInputs);

    // fetch updated data from the DB
    List<Map<String,Object>> customersTableData = jdbcTemplate.queryForList("SELECT * FROM Customers;");
    List<Map<String,Object>> checkingTransactionHistoryTableData = jdbcTemplate.queryForList("SELECT * FROM CheckingTransactionHistory;");
    List<Map<String,Object>> savingsTransactionHistoryTableData = jdbcTemplate.queryForList("SELECT * FROM SavingsTransactionHistory;");

    // verify that customer1's data is still the only data populated in Customers table
    assertEquals(1, customersTableData.size());
    Map<String,Object> customer1Data = customersTableData.get(0);
    assertEquals(CUSTOMER1_ID, (String)customer1Data.get("CustomerID"));

    // verify customer balance was increased by $12.34
    double CUSTOMER1_EXPECTED_FINAL_BALANCE = CUSTOMER1_BALANCE + CUSTOMER1_AMOUNT_TO_DEPOSIT;
    double CUSTOMER1_EXPECTED_FINAL_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_EXPECTED_FINAL_BALANCE);
    assertEquals(CUSTOMER1_EXPECTED_FINAL_BALANCE_IN_PENNIES, (int)customer1Data.get("CheckingBalance"));

    // verify that the Deposit is the only log in TransactionHistory table
    assertEquals(1, checkingTransactionHistoryTableData.size());
    assertEquals(0, savingsTransactionHistoryTableData.size());

    // verify that the Deposit's details are accurately logged in the CheckingTransactionHistory table
    Map<String,Object> customer1CheckingTransactionLog = checkingTransactionHistoryTableData.get(0);
    int CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_AMOUNT_TO_DEPOSIT);
    MvcControllerIntegTestHelpers.checkTransactionLog(customer1CheckingTransactionLog, timeWhenDepositRequestSent, CUSTOMER1_ID, MvcController.TRANSACTION_HISTORY_DEPOSIT_ACTION, CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES);

    // verify that the savings balance has not changed
    assertEquals(CUSTOMER1_SAVINGS_BALANCE_IN_PENNIES, (int)customer1Data.get("SavingsBalance"));
  }

    /**
   * Verifies the simplest deposit case for a savings account only, where the 
   * initial checking balance is not 0. The customer's SavingsBalance in the 
   * Customers table should be increased, and the Deposit should be logged 
   * in the SavingsTransactionHistory table. The CheckingTransactionHistory table
   * shoule be empty, and the checking balance should not change. 
   * 
   * Assumes that the customer's account is in the simplest state
   * (not in overdraft, account is not frozen due to too many transaction disputes, etc.)
   * 
   * @throws SQLException
   * @throws ScriptException
   */
  @Test
  public void testSimpleDepositSavingsCheckingNotZero() throws SQLException, ScriptException {
    // initialize customer1 with a balance of $123.45 (to make sure this works for non-whole dollar amounts). represented as pennies in the DB.
    double CUSTOMER1_BALANCE = 123.45;
    int CUSTOMER1_SAVINGS_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_BALANCE);
    double CUSTOMER1_CHECKING_BALANCE = 100;
    int CUSTOMER1_CHECKING_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_CHECKING_BALANCE);

    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER1_ID, CUSTOMER1_PASSWORD, CUSTOMER1_FIRST_NAME, CUSTOMER1_LAST_NAME, CUSTOMER1_CHECKING_BALANCE_IN_PENNIES, CUSTOMER1_SAVINGS_BALANCE_IN_PENNIES, 0);

    // Prepare Deposit Form to Deposit $12.34 to customer 1's account.
    double CUSTOMER1_AMOUNT_TO_DEPOSIT = 12.34; // user input is in dollar amount, not pennies.
    User customer1DepositFormInputs = new User();
    customer1DepositFormInputs.setUsername(CUSTOMER1_ID);
    customer1DepositFormInputs.setPassword(CUSTOMER1_PASSWORD);
    customer1DepositFormInputs.setAmountToDeposit(CUSTOMER1_AMOUNT_TO_DEPOSIT); 

    // verify that there are no logs in TransactionHistory table before Deposit
    assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM CheckingTransactionHistory;", Integer.class));
    assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM SavingsTransactionHistory;", Integer.class));

    // store timestamp of when Deposit request is sent to verify timestamps in the TransactionHistory table later
    LocalDateTime timeWhenDepositRequestSent = MvcControllerIntegTestHelpers.fetchCurrentTimeAsLocalDateTimeNoMilliseconds();
    System.out.println("Timestamp when Deposit Request is sent: " + timeWhenDepositRequestSent);

    // send request to the Deposit Form's POST handler in MvcController
    controller.submitDeposit(ACCOUNT_TYPE_SAVINGS, customer1DepositFormInputs);

    // fetch updated data from the DB
    List<Map<String,Object>> customersTableData = jdbcTemplate.queryForList("SELECT * FROM Customers;");
    List<Map<String,Object>> checkingTransactionHistoryTableData = jdbcTemplate.queryForList("SELECT * FROM CheckingTransactionHistory;");
    List<Map<String,Object>> savingsTransactionHistoryTableData = jdbcTemplate.queryForList("SELECT * FROM SavingsTransactionHistory;");

    // verify that customer1's data is still the only data populated in Customers table
    assertEquals(1, customersTableData.size());
    Map<String,Object> customer1Data = customersTableData.get(0);
    assertEquals(CUSTOMER1_ID, (String)customer1Data.get("CustomerID"));

    // verify customer balance was increased by $12.34
    double CUSTOMER1_EXPECTED_FINAL_BALANCE = CUSTOMER1_BALANCE + CUSTOMER1_AMOUNT_TO_DEPOSIT;
    double CUSTOMER1_EXPECTED_FINAL_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_EXPECTED_FINAL_BALANCE);
    assertEquals(CUSTOMER1_EXPECTED_FINAL_BALANCE_IN_PENNIES, (int)customer1Data.get("SavingsBalance"));

    // verify that the Deposit is the only log in TransactionHistory table
    assertEquals(0, checkingTransactionHistoryTableData.size());
    assertEquals(1, savingsTransactionHistoryTableData.size());

    // verify that the Deposit's details are accurately logged in the CheckingTransactionHistory table
    Map<String,Object> customer1SavingsTransactionLog = savingsTransactionHistoryTableData.get(0);
    int CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_AMOUNT_TO_DEPOSIT);
    MvcControllerIntegTestHelpers.checkTransactionLog(customer1SavingsTransactionLog, timeWhenDepositRequestSent, CUSTOMER1_ID, MvcController.TRANSACTION_HISTORY_DEPOSIT_ACTION, CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES);

    // verify that the savings balance has not changed
    assertEquals(CUSTOMER1_CHECKING_BALANCE_IN_PENNIES, (int)customer1Data.get("CheckingBalance"));
  }

  /**
   * Verifies the simplest withdraw case for the checking account.
   * The customer's CheckingBalance in the Customers table should be decreased,
   * and the Withdraw should be logged in the CheckingTransactionHistory table.
   * 
   * Assumes that the customer's account is in the simplest state
   * (not already in overdraft, the withdraw does not put customer in overdraft,
   *  account is not frozen due to too many transaction disputes, etc.)
   * 
   * @throws SQLException
   * @throws ScriptException
   */
  @Test
  public void testSimpleWithdrawChecking() throws SQLException, ScriptException {
    // initialize customer1 with a balance of $123.45 (to make sure this works for non-whole dollar amounts). represented as pennies in the DB.
    double CUSTOMER1_BALANCE = 123.45;
    int CUSTOMER1_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_BALANCE);
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER1_ID, CUSTOMER1_PASSWORD, CUSTOMER1_FIRST_NAME, CUSTOMER1_LAST_NAME, CUSTOMER1_BALANCE_IN_PENNIES, 0, 0);

    // Prepare Withdraw Form to Withdraw $12.34 from customer 1's account.
    double CUSTOMER1_AMOUNT_TO_WITHDRAW = 12.34; // user input is in dollar amount, not pennies.
    User customer1WithdrawFormInputs = new User();
    customer1WithdrawFormInputs.setUsername(CUSTOMER1_ID);
    customer1WithdrawFormInputs.setPassword(CUSTOMER1_PASSWORD);
    customer1WithdrawFormInputs.setAmountToWithdraw(CUSTOMER1_AMOUNT_TO_WITHDRAW); // user input is in dollar amount, not pennies.

    // verify that there are no logs in TransactionHistory table before Withdraw
    assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM CheckingTransactionHistory;", Integer.class));

    // store timestamp of when Withdraw request is sent to verify timestamps in the TransactionHistory table later
    LocalDateTime timeWhenWithdrawRequestSent = MvcControllerIntegTestHelpers.fetchCurrentTimeAsLocalDateTimeNoMilliseconds();
    System.out.println("Timestamp when Withdraw Request is sent: " + timeWhenWithdrawRequestSent);

    // send request to the Withdraw Form's POST handler in MvcController
    controller.submitWithdraw(ACCOUNT_TYPE_CHECKING, customer1WithdrawFormInputs);

    // fetch updated data from the DB
    List<Map<String,Object>> customersTableData = jdbcTemplate.queryForList("SELECT * FROM Customers;");
    List<Map<String,Object>> transactionHistoryTableData = jdbcTemplate.queryForList("SELECT * FROM CheckingTransactionHistory;");
  
    // verify that customer1's data is still the only data populated in Customers table
    assertEquals(1, customersTableData.size());
    Map<String,Object> customer1Data = customersTableData.get(0);
    assertEquals(CUSTOMER1_ID, (String)customer1Data.get("CustomerID"));

    // verify customer balance was decreased by $12.34
    double CUSTOMER1_EXPECTED_FINAL_BALANCE = CUSTOMER1_BALANCE - CUSTOMER1_AMOUNT_TO_WITHDRAW;
    double CUSTOMER1_EXPECTED_FINAL_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_EXPECTED_FINAL_BALANCE);
    assertEquals(CUSTOMER1_EXPECTED_FINAL_BALANCE_IN_PENNIES, (int)customer1Data.get("CheckingBalance"));

    // verify that the Withdraw is the only log in TransactionHistory table
    assertEquals(1, transactionHistoryTableData.size());

    // verify that the Withdraw's details are accurately logged in the TransactionHistory table
    Map<String,Object> customer1TransactionLog = transactionHistoryTableData.get(0);
    int CUSTOMER1_AMOUNT_TO_WITHDRAW_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_AMOUNT_TO_WITHDRAW);
    MvcControllerIntegTestHelpers.checkTransactionLog(customer1TransactionLog, timeWhenWithdrawRequestSent, CUSTOMER1_ID, MvcController.TRANSACTION_HISTORY_WITHDRAW_ACTION, CUSTOMER1_AMOUNT_TO_WITHDRAW_IN_PENNIES);
  }

    /**
   * Verifies the simplest withdraw case for the savings account.
   * The customer's SavingsBalance in the Customers table should be decreased,
   * and the Withdraw should be logged in the SavingsTransactionHistory table.
   * 
   * Assumes that the customer's account is in the simplest state
   * (not already in overdraft, the withdraw does not put customer in overdraft,
   *  account is not frozen due to too many transaction disputes, etc.)
   * 
   * @throws SQLException
   * @throws ScriptException
   */
  @Test
  public void testSimpleWithdrawSavings() throws SQLException, ScriptException {
    // initialize customer1 with a balance of $123.45 (to make sure this works for non-whole dollar amounts). represented as pennies in the DB.
    double CUSTOMER1_BALANCE = 123.45;
    int CUSTOMER1_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_BALANCE);
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER1_ID, CUSTOMER1_PASSWORD, CUSTOMER1_FIRST_NAME, CUSTOMER1_LAST_NAME, 0, CUSTOMER1_BALANCE_IN_PENNIES, 0);

    // Prepare Withdraw Form to Withdraw $12.34 from customer 1's account.
    double CUSTOMER1_AMOUNT_TO_WITHDRAW = 12.34; // user input is in dollar amount, not pennies.
    User customer1WithdrawFormInputs = new User();
    customer1WithdrawFormInputs.setUsername(CUSTOMER1_ID);
    customer1WithdrawFormInputs.setPassword(CUSTOMER1_PASSWORD);
    customer1WithdrawFormInputs.setAmountToWithdraw(CUSTOMER1_AMOUNT_TO_WITHDRAW); // user input is in dollar amount, not pennies.

    // verify that there are no logs in TransactionHistory table before Withdraw
    assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM SavingsTransactionHistory;", Integer.class));

    // store timestamp of when Withdraw request is sent to verify timestamps in the TransactionHistory table later
    LocalDateTime timeWhenWithdrawRequestSent = MvcControllerIntegTestHelpers.fetchCurrentTimeAsLocalDateTimeNoMilliseconds();
    System.out.println("Timestamp when Withdraw Request is sent: " + timeWhenWithdrawRequestSent);

    // send request to the Withdraw Form's POST handler in MvcController
    controller.submitWithdraw(ACCOUNT_TYPE_SAVINGS, customer1WithdrawFormInputs);

    // fetch updated data from the DB
    List<Map<String,Object>> customersTableData = jdbcTemplate.queryForList("SELECT * FROM Customers;");
    List<Map<String,Object>> transactionHistoryTableData = jdbcTemplate.queryForList("SELECT * FROM SavingsTransactionHistory;");
  
    // verify that customer1's data is still the only data populated in Customers table
    assertEquals(1, customersTableData.size());
    Map<String,Object> customer1Data = customersTableData.get(0);
    assertEquals(CUSTOMER1_ID, (String)customer1Data.get("CustomerID"));

    // verify customer balance was decreased by $12.34
    double CUSTOMER1_EXPECTED_FINAL_BALANCE = CUSTOMER1_BALANCE - CUSTOMER1_AMOUNT_TO_WITHDRAW;
    double CUSTOMER1_EXPECTED_FINAL_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_EXPECTED_FINAL_BALANCE);
    assertEquals(CUSTOMER1_EXPECTED_FINAL_BALANCE_IN_PENNIES, (int)customer1Data.get("SavingsBalance"));

    // verify that the Withdraw is the only log in TransactionHistory table
    assertEquals(1, transactionHistoryTableData.size());

    // verify that the Withdraw's details are accurately logged in the TransactionHistory table
    Map<String,Object> customer1TransactionLog = transactionHistoryTableData.get(0);
    int CUSTOMER1_AMOUNT_TO_WITHDRAW_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_AMOUNT_TO_WITHDRAW);
    MvcControllerIntegTestHelpers.checkTransactionLog(customer1TransactionLog, timeWhenWithdrawRequestSent, CUSTOMER1_ID, MvcController.TRANSACTION_HISTORY_WITHDRAW_ACTION, CUSTOMER1_AMOUNT_TO_WITHDRAW_IN_PENNIES);
  }

  /**
   * Verifies the case where a customer withdraws more than their available balance
   * for their checking account. The customer's main checking balance should be 
   * set to $0, and their CheckingOverdraft balance should be the remaining 
   * withdraw amount with interest applied.
   * 
   * This Withdraw should still be recorded in the CheckingTransactionHistory table.
   * 
   * A few Assertions are omitted to remove clutter since they are already
   * checked in detail in testSimpleWithdrawChecking().
   * 
   * @throws SQLException
   * @throws ScriptException
   */
  @Test
  public void testWithdrawTriggersOverdraftChecking() throws SQLException, ScriptException {
    // initialize customer1 with a balance of $123.45 (to make sure this works for non-whole dollar amounts). represented as pennies in the DB.
    double CUSTOMER1_BALANCE = 123.45;
    int CUSTOMER1_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_BALANCE);
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER1_ID, CUSTOMER1_PASSWORD, CUSTOMER1_FIRST_NAME, CUSTOMER1_LAST_NAME, CUSTOMER1_BALANCE_IN_PENNIES, 0, 0);

    // Prepare Withdraw Form to Withdraw $150 from customer 1's account.
    double CUSTOMER1_AMOUNT_TO_WITHDRAW = 150; // user input is in dollar amount, not pennies.
    User customer1WithdrawFormInputs = new User();
    customer1WithdrawFormInputs.setUsername(CUSTOMER1_ID);
    customer1WithdrawFormInputs.setPassword(CUSTOMER1_PASSWORD);
    customer1WithdrawFormInputs.setAmountToWithdraw(CUSTOMER1_AMOUNT_TO_WITHDRAW); // user input is in dollar amount, not pennies.

    // store timestamp of when Withdraw request is sent to verify timestamps in the TransactionHistory table later
    LocalDateTime timeWhenWithdrawRequestSent = MvcControllerIntegTestHelpers.fetchCurrentTimeAsLocalDateTimeNoMilliseconds();
    System.out.println("Timestamp when Withdraw Request is sent: " + timeWhenWithdrawRequestSent);

    // send request to the Withdraw Form's POST handler in MvcController
    controller.submitWithdraw(ACCOUNT_TYPE_CHECKING, customer1WithdrawFormInputs);

    // fetch updated customer1 data from the DB
    List<Map<String,Object>> customersTableData = jdbcTemplate.queryForList("SELECT * FROM Customers;");
    List<Map<String,Object>> transactionHistoryTableData = jdbcTemplate.queryForList("SELECT * FROM CheckingTransactionHistory;");
    
    // verify that customer1's main balance is now 0
    Map<String,Object> customer1Data = customersTableData.get(0);
    assertEquals(0, (int)customer1Data.get("CheckingBalance"));

    // verify that customer1's Overdraft balance is equal to the remaining withdraw amount with interest applied
    // (convert to pennies before applying interest rate to avoid floating point roundoff errors when applying the interest rate)
    int CUSTOMER1_ORIGINAL_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_BALANCE);
    int CUSTOMER1_AMOUNT_TO_WITHDRAW_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_AMOUNT_TO_WITHDRAW);
    int CUSTOMER1_EXPECTED_OVERDRAFT_BALANCE_BEFORE_INTEREST_IN_PENNIES = CUSTOMER1_AMOUNT_TO_WITHDRAW_IN_PENNIES - CUSTOMER1_ORIGINAL_BALANCE_IN_PENNIES;
    int CUSTOMER1_EXPECTED_OVERDRAFT_BALANCE_AFTER_INTEREST_IN_PENNIES = MvcControllerIntegTestHelpers.applyOverdraftInterest(CUSTOMER1_EXPECTED_OVERDRAFT_BALANCE_BEFORE_INTEREST_IN_PENNIES);
    System.out.println("Expected Overdraft Balance in pennies: " + CUSTOMER1_EXPECTED_OVERDRAFT_BALANCE_AFTER_INTEREST_IN_PENNIES);
    assertEquals(CUSTOMER1_EXPECTED_OVERDRAFT_BALANCE_AFTER_INTEREST_IN_PENNIES, (int)customer1Data.get("CheckingOverdraftBalance"));

    // verify that the Withdraw's details are accurately logged in the TransactionHistory table
    Map<String,Object> customer1TransactionLog = transactionHistoryTableData.get(0);
    MvcControllerIntegTestHelpers.checkTransactionLog(customer1TransactionLog, timeWhenWithdrawRequestSent, CUSTOMER1_ID, MvcController.TRANSACTION_HISTORY_WITHDRAW_ACTION, CUSTOMER1_AMOUNT_TO_WITHDRAW_IN_PENNIES);
  }

    /**
   * Verifies the case where a customer withdraws more than their available balance
   * for their savings account. The customer's main savings balance should be 
   * set to $0, and their SavingsOverdraft balance should be the remaining 
   * withdraw amount with interest applied.
   * 
   * This Withdraw should still be recorded in the SavingsTransactionHistory table.
   * 
   * A few Assertions are omitted to remove clutter since they are already
   * checked in detail in testSimpleWithdrawSavings().
   * 
   * @throws SQLException
   * @throws ScriptException
   */
  @Test
  public void testWithdrawTriggersOverdraftSavings() throws SQLException, ScriptException {
    // initialize customer1 with a balance of $123.45 (to make sure this works for non-whole dollar amounts). represented as pennies in the DB.
    double CUSTOMER1_BALANCE = 123.45;
    int CUSTOMER1_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_BALANCE);
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER1_ID, CUSTOMER1_PASSWORD, CUSTOMER1_FIRST_NAME, CUSTOMER1_LAST_NAME, 0, CUSTOMER1_BALANCE_IN_PENNIES, 0);

    // Prepare Withdraw Form to Withdraw $150 from customer 1's account.
    double CUSTOMER1_AMOUNT_TO_WITHDRAW = 150; // user input is in dollar amount, not pennies.
    User customer1WithdrawFormInputs = new User();
    customer1WithdrawFormInputs.setUsername(CUSTOMER1_ID);
    customer1WithdrawFormInputs.setPassword(CUSTOMER1_PASSWORD);
    customer1WithdrawFormInputs.setAmountToWithdraw(CUSTOMER1_AMOUNT_TO_WITHDRAW); // user input is in dollar amount, not pennies.

    // store timestamp of when Withdraw request is sent to verify timestamps in the TransactionHistory table later
    LocalDateTime timeWhenWithdrawRequestSent = MvcControllerIntegTestHelpers.fetchCurrentTimeAsLocalDateTimeNoMilliseconds();
    System.out.println("Timestamp when Withdraw Request is sent: " + timeWhenWithdrawRequestSent);

    // send request to the Withdraw Form's POST handler in MvcController
    controller.submitWithdraw(ACCOUNT_TYPE_SAVINGS, customer1WithdrawFormInputs);

    // fetch updated customer1 data from the DB
    List<Map<String,Object>> customersTableData = jdbcTemplate.queryForList("SELECT * FROM Customers;");
    List<Map<String,Object>> transactionHistoryTableData = jdbcTemplate.queryForList("SELECT * FROM SavingsTransactionHistory;");
    
    // verify that customer1's main balance is now 0
    Map<String,Object> customer1Data = customersTableData.get(0);
    assertEquals(0, (int)customer1Data.get("SavingsBalance"));

    // verify that customer1's Overdraft balance is equal to the remaining withdraw amount with interest applied
    // (convert to pennies before applying interest rate to avoid floating point roundoff errors when applying the interest rate)
    int CUSTOMER1_ORIGINAL_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_BALANCE);
    int CUSTOMER1_AMOUNT_TO_WITHDRAW_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_AMOUNT_TO_WITHDRAW);
    int CUSTOMER1_EXPECTED_OVERDRAFT_BALANCE_BEFORE_INTEREST_IN_PENNIES = CUSTOMER1_AMOUNT_TO_WITHDRAW_IN_PENNIES - CUSTOMER1_ORIGINAL_BALANCE_IN_PENNIES;
    int CUSTOMER1_EXPECTED_OVERDRAFT_BALANCE_AFTER_INTEREST_IN_PENNIES = MvcControllerIntegTestHelpers.applyOverdraftInterest(CUSTOMER1_EXPECTED_OVERDRAFT_BALANCE_BEFORE_INTEREST_IN_PENNIES);
    System.out.println("Expected Overdraft Balance in pennies: " + CUSTOMER1_EXPECTED_OVERDRAFT_BALANCE_AFTER_INTEREST_IN_PENNIES);
    assertEquals(CUSTOMER1_EXPECTED_OVERDRAFT_BALANCE_AFTER_INTEREST_IN_PENNIES, (int)customer1Data.get("SavingsOverdraftBalance"));

    // verify that the Withdraw's details are accurately logged in the TransactionHistory table
    Map<String,Object> customer1TransactionLog = transactionHistoryTableData.get(0);
    MvcControllerIntegTestHelpers.checkTransactionLog(customer1TransactionLog, timeWhenWithdrawRequestSent, CUSTOMER1_ID, MvcController.TRANSACTION_HISTORY_WITHDRAW_ACTION, CUSTOMER1_AMOUNT_TO_WITHDRAW_IN_PENNIES);
  }

  /**
   * The customer will be given an initial checking balance of $100 and will withdraw $1099.
   * This will test the scenario where the withdraw excess amount, which is $100 - $1099 = -$999,
   * results in a valid overdraft balance, but once the 2% interest rate is applied, will cross the limit
   * of $1000. 
   * 
   * This test checks to make sure that the customer's checking balance stays the same as before due to a failed 
   * withdraw request, and checks that the CheckingTransactionHistory table is empty.
   * 
   * @throws SQLException
   * @throws ScriptException
   */
  @Test
  public void testWithdrawOverdraftLimitExceededChecking() throws SQLException, ScriptException { 
    //initialize customer1 with a balance of $100. this will be represented as pennies in DB.
    double CUSTOMER1_BALANCE = 100;
    int CUSTOMER1_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_BALANCE);
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER1_ID, CUSTOMER1_PASSWORD, CUSTOMER1_FIRST_NAME, CUSTOMER1_LAST_NAME, CUSTOMER1_BALANCE_IN_PENNIES, 0, 0);

    //Prepare Withdraw Form to withdraw $1099 from this customer's account.
    double CUSTOMER1_AMOUNT_TO_WITHDRAW = 1099; 
    User customer1WithdrawFormInputs = new User();
    customer1WithdrawFormInputs.setUsername(CUSTOMER1_ID);
    customer1WithdrawFormInputs.setPassword(CUSTOMER1_PASSWORD);
    customer1WithdrawFormInputs.setAmountToWithdraw(CUSTOMER1_AMOUNT_TO_WITHDRAW);

    //Store the timestamp of the withdraw request to verify it in the TransactionHistory table later
    LocalDateTime timeWhenWithdrawRequestSent = MvcControllerIntegTestHelpers.fetchCurrentTimeAsLocalDateTimeNoMilliseconds();
    System.out.println("Timestamp when withdraw request sent: " + timeWhenWithdrawRequestSent);

    //Check the response when the withdraw request is submitted. This should return the user back to the home screen due to an invalid request
    String responsePage = controller.submitWithdraw(ACCOUNT_TYPE_CHECKING, customer1WithdrawFormInputs);
    assertEquals("welcome", responsePage);

    //Fetch customer1's data from DB
    List<Map<String, Object>> customersTableData = jdbcTemplate.queryForList("SELECT * FROM Customers;");

    //Since the request did not go through, the balance is supposed to stay the same.
    Map<String, Object> customer1Data = customersTableData.get(0);
    assertEquals(CUSTOMER1_BALANCE_IN_PENNIES, (int)customer1Data.get("CheckingBalance"));

    //Checks to make sure that the overdraft balance was not increased
    assertEquals(0, (int)customer1Data.get("CheckingOverdraftBalance"));

    //check that TransactionHistory table is empty
    List<Map<String, Object>> transactionHistoryTableData = jdbcTemplate.queryForList("SELECT * FROM CheckingTransactionHistory;");
    assertTrue(transactionHistoryTableData.isEmpty());

  }

    /**
   * The customer will be given an initial savings balance of $100 and will withdraw $1099.
   * This will test the scenario where the withdraw excess amount, which is $100 - $1099 = -$999,
   * results in a valid overdraft balance, but once the 2% interest rate is applied, will cross the limit
   * of $1000. 
   * 
   * This test checks to make sure that the customer's savings balance stays the same as before due to a failed 
   * withdraw request, and checks that the CheckingTransactionHistory table is empty.
   * 
   * @throws SQLException
   * @throws ScriptException
   */
  @Test
  public void testWithdrawOverdraftLimitExceededSavings() throws SQLException, ScriptException { 
    //initialize customer1 with a balance of $100. this will be represented as pennies in DB.
    double CUSTOMER1_BALANCE = 100;
    int CUSTOMER1_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_BALANCE);
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER1_ID, CUSTOMER1_PASSWORD, CUSTOMER1_FIRST_NAME, CUSTOMER1_LAST_NAME, 0, CUSTOMER1_BALANCE_IN_PENNIES, 0);

    //Prepare Withdraw Form to withdraw $1099 from this customer's account.
    double CUSTOMER1_AMOUNT_TO_WITHDRAW = 1099; 
    User customer1WithdrawFormInputs = new User();
    customer1WithdrawFormInputs.setUsername(CUSTOMER1_ID);
    customer1WithdrawFormInputs.setPassword(CUSTOMER1_PASSWORD);
    customer1WithdrawFormInputs.setAmountToWithdraw(CUSTOMER1_AMOUNT_TO_WITHDRAW);

    //Store the timestamp of the withdraw request to verify it in the TransactionHistory table later
    LocalDateTime timeWhenWithdrawRequestSent = MvcControllerIntegTestHelpers.fetchCurrentTimeAsLocalDateTimeNoMilliseconds();
    System.out.println("Timestamp when withdraw request sent: " + timeWhenWithdrawRequestSent);

    //Check the response when the withdraw request is submitted. This should return the user back to the home screen due to an invalid request
    String responsePage = controller.submitWithdraw(ACCOUNT_TYPE_SAVINGS, customer1WithdrawFormInputs);
    assertEquals("welcome", responsePage);

    //Fetch customer1's data from DB
    List<Map<String, Object>> customersTableData = jdbcTemplate.queryForList("SELECT * FROM Customers;");

    //Since the request did not go through, the balance is supposed to stay the same.
    Map<String, Object> customer1Data = customersTableData.get(0);
    assertEquals(CUSTOMER1_BALANCE_IN_PENNIES, (int)customer1Data.get("SavingsBalance"));

    //Checks to make sure that the overdraft balance was not increased
    assertEquals(0, (int)customer1Data.get("SavingsOverdraftBalance"));

    //check that TransactionHistory table is empty
    List<Map<String, Object>> transactionHistoryTableData = jdbcTemplate.queryForList("SELECT * FROM SavingsTransactionHistory;");
    assertTrue(transactionHistoryTableData.isEmpty());

  }

  /**
   * Verifies the case where a customer is in overdraft in their checking account 
   * and deposits an amount that exceeds their overdraft for the checking account 
   * balance. The customer's CheckingOverdraftBalance
   * in the Customers table should be set to $0, and their main CheckingBalance
   * should be set to the excess deposit amount.
   * 
   * This Deposit should be logged in the CheckingOverdraftLogs table since it is a repayment.
   * 
   * This Deposit should still be recorded in the CheckingTransactionHistory table.
   * 
   * A few Assertions are omitted to remove clutter since they are already
   * checked in detail in testSimpleDeposit().
   * 
   * @throws SQLException
   * @throws ScriptException
   */
  @Test
  public void testCheckingDepositOverdraftClearedWithExcess() throws SQLException, ScriptException {
    // initialize customer1 with an overdraft balance of $123.45 (to make sure this works for non-whole dollar amounts). represented as pennies in the DB.
    int CUSTOMER1_MAIN_BALANCE_IN_PENNIES = 0;
    double CUSTOMER1_OVERDRAFT_BALANCE = 123.45;
    int CUSTOMER1_OVERDRAFT_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_OVERDRAFT_BALANCE);
    int CUSTOMER1_NUM_FRAUD_REVERSALS = 0;
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER1_ID, CUSTOMER1_PASSWORD, CUSTOMER1_FIRST_NAME, CUSTOMER1_LAST_NAME, CUSTOMER1_MAIN_BALANCE_IN_PENNIES, 0, CUSTOMER1_OVERDRAFT_BALANCE_IN_PENNIES, 0, CUSTOMER1_NUM_FRAUD_REVERSALS, 0);
    
    // Prepare Deposit Form to Deposit $150 to customer 1's account.
    double CUSTOMER1_AMOUNT_TO_DEPOSIT = 150; // user input is in dollar amount, not pennies.
    User customer1DepositFormInputs = new User();
    customer1DepositFormInputs.setUsername(CUSTOMER1_ID);
    customer1DepositFormInputs.setPassword(CUSTOMER1_PASSWORD);
    customer1DepositFormInputs.setAmountToDeposit(CUSTOMER1_AMOUNT_TO_DEPOSIT); 

    // store timestamp of when Deposit request is sent to verify timestamps in the TransactionHistory and OverdraftLogs tables later
    LocalDateTime timeWhenDepositRequestSent = MvcControllerIntegTestHelpers.fetchCurrentTimeAsLocalDateTimeNoMilliseconds();
    System.out.println("Timestamp when Deposit Request is sent: " + timeWhenDepositRequestSent);

    // send request to the Deposit Form's POST handler in MvcController
    controller.submitDeposit(ACCOUNT_TYPE_CHECKING, customer1DepositFormInputs);

    // fetch updated data from the DB
    List<Map<String,Object>> customersTableData = jdbcTemplate.queryForList("SELECT * FROM Customers;");
    List<Map<String,Object>> transactionHistoryTableData = jdbcTemplate.queryForList("SELECT * FROM CheckingTransactionHistory;");
    List<Map<String,Object>> overdraftLogsTableData = jdbcTemplate.queryForList("SELECT * FROM CheckingOverdraftLogs;");

    // verify that customer's overdraft balance is now $0
    Map<String,Object> customer1Data = customersTableData.get(0);
    assertEquals(0, (int)customer1Data.get("CheckingOverdraftBalance"));

    // verify that the customer's main balance is now $50 due to the excess deposit amount
    int CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_AMOUNT_TO_DEPOSIT);
    int CUSTOMER1_ORIGINAL_OVERDRAFT_BALANCE_IN_PENNIES = CUSTOMER1_OVERDRAFT_BALANCE_IN_PENNIES;
    int CUSTOMER1_EXPECTED_MAIN_BALANCE_IN_PENNIES = CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES - CUSTOMER1_ORIGINAL_OVERDRAFT_BALANCE_IN_PENNIES;
    assertEquals(CUSTOMER1_EXPECTED_MAIN_BALANCE_IN_PENNIES, (int)customer1Data.get("CheckingBalance"));

    // verify that the deposit is logged properly in the OverdraftLogs table
    Map<String,Object> customer1OverdraftLog = overdraftLogsTableData.get(0);
    MvcControllerIntegTestHelpers.checkOverdraftLog(customer1OverdraftLog, timeWhenDepositRequestSent, CUSTOMER1_ID, CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES, CUSTOMER1_ORIGINAL_OVERDRAFT_BALANCE_IN_PENNIES, 0);

    // verify that the Deposit's details are accurately logged in the TransactionHistory table
    Map<String,Object> customer1TransactionLog = transactionHistoryTableData.get(0);
    MvcControllerIntegTestHelpers.checkTransactionLog(customer1TransactionLog, timeWhenDepositRequestSent, CUSTOMER1_ID, MvcController.TRANSACTION_HISTORY_DEPOSIT_ACTION, CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES);
  }

  /**
   * Verifies the case where a customer is in overdraft in their savings account 
   * and deposits an amount that exceeds their overdraft for the savings account 
   * balance. The customer's SavingsOverdraftBalance
   * in the Customers table should be set to $0, and their main SavingsBalance
   * should be set to the excess deposit amount.
   * 
   * This Deposit should be logged in the SavingsOverdraftLogs table since it is a repayment.
   * 
   * This Deposit should still be recorded in the SavingsTransactionHistory table.
   * 
   * A few Assertions are omitted to remove clutter since they are already
   * checked in detail in testSimpleDeposit().
   * 
   * @throws SQLException
   * @throws ScriptException
   */
  @Test
  public void testSavingsDepositOverdraftClearedWithExcess() throws SQLException, ScriptException {
    // initialize customer1 with an overdraft balance of $123.45 (to make sure this works for non-whole dollar amounts). represented as pennies in the DB.
    int CUSTOMER1_MAIN_BALANCE_IN_PENNIES = 0;
    double CUSTOMER1_OVERDRAFT_BALANCE = 123.45;
    int CUSTOMER1_OVERDRAFT_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_OVERDRAFT_BALANCE);
    int CUSTOMER1_NUM_FRAUD_REVERSALS = 0;
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER1_ID, CUSTOMER1_PASSWORD, CUSTOMER1_FIRST_NAME, CUSTOMER1_LAST_NAME, 0, CUSTOMER1_MAIN_BALANCE_IN_PENNIES, 0, CUSTOMER1_OVERDRAFT_BALANCE_IN_PENNIES, CUSTOMER1_NUM_FRAUD_REVERSALS, 0);
    
    // Prepare Deposit Form to Deposit $150 to customer 1's account.
    double CUSTOMER1_AMOUNT_TO_DEPOSIT = 150; // user input is in dollar amount, not pennies.
    User customer1DepositFormInputs = new User();
    customer1DepositFormInputs.setUsername(CUSTOMER1_ID);
    customer1DepositFormInputs.setPassword(CUSTOMER1_PASSWORD);
    customer1DepositFormInputs.setAmountToDeposit(CUSTOMER1_AMOUNT_TO_DEPOSIT); 

    // store timestamp of when Deposit request is sent to verify timestamps in the TransactionHistory and OverdraftLogs tables later
    LocalDateTime timeWhenDepositRequestSent = MvcControllerIntegTestHelpers.fetchCurrentTimeAsLocalDateTimeNoMilliseconds();
    System.out.println("Timestamp when Deposit Request is sent: " + timeWhenDepositRequestSent);

    // send request to the Deposit Form's POST handler in MvcController
    controller.submitDeposit(ACCOUNT_TYPE_SAVINGS, customer1DepositFormInputs);

    // fetch updated data from the DB
    List<Map<String,Object>> customersTableData = jdbcTemplate.queryForList("SELECT * FROM Customers;");
    List<Map<String,Object>> transactionHistoryTableData = jdbcTemplate.queryForList("SELECT * FROM SavingsTransactionHistory;");
    List<Map<String,Object>> overdraftLogsTableData = jdbcTemplate.queryForList("SELECT * FROM SavingsOverdraftLogs;");

    // verify that customer's overdraft balance is now $0
    Map<String,Object> customer1Data = customersTableData.get(0);
    assertEquals(0, (int)customer1Data.get("SavingsOverdraftBalance"));

    // verify that the customer's main balance is now $50 due to the excess deposit amount
    int CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_AMOUNT_TO_DEPOSIT);
    int CUSTOMER1_ORIGINAL_OVERDRAFT_BALANCE_IN_PENNIES = CUSTOMER1_OVERDRAFT_BALANCE_IN_PENNIES;
    int CUSTOMER1_EXPECTED_MAIN_BALANCE_IN_PENNIES = CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES - CUSTOMER1_ORIGINAL_OVERDRAFT_BALANCE_IN_PENNIES;
    assertEquals(CUSTOMER1_EXPECTED_MAIN_BALANCE_IN_PENNIES, (int)customer1Data.get("SavingsBalance"));

    // verify that the deposit is logged properly in the OverdraftLogs table
    Map<String,Object> customer1OverdraftLog = overdraftLogsTableData.get(0);
    MvcControllerIntegTestHelpers.checkOverdraftLog(customer1OverdraftLog, timeWhenDepositRequestSent, CUSTOMER1_ID, CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES, CUSTOMER1_ORIGINAL_OVERDRAFT_BALANCE_IN_PENNIES, 0);

    // verify that the Deposit's details are accurately logged in the TransactionHistory table
    Map<String,Object> customer1TransactionLog = transactionHistoryTableData.get(0);
    MvcControllerIntegTestHelpers.checkTransactionLog(customer1TransactionLog, timeWhenDepositRequestSent, CUSTOMER1_ID, MvcController.TRANSACTION_HISTORY_DEPOSIT_ACTION, CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES);
  }

  /**
   * Verifies the case where a customer is in overdraft in the checking account 
   * and deposits an amount that still leaves some leftover CheckingOverdraft balance.
   * The customer's CheckingOverdraftBalance in the Customers table should be 
   * set to $0, and their main CheckingBalance should still be $0 in the MySQL DB.
   * 
   * This Deposit should be logged in the CheckingOverdraftLogs table since it is a repayment.
   * 
   * This Deposit should still be recorded in the CheckingTransactionHistory table.
   * 
   * A few Assertions are omitted to remove clutter since they are already
   * checked in detail in testSimpleDeposit().
   * 
   * @throws SQLException
   * @throws ScriptException
   */
  @Test
  public void testCheckingDepositOverdraftNotCleared() throws SQLException, ScriptException {
    // initialize customer1 with an overdraft balance of $123.45 (to make sure this works for non-whole dollar amounts). represented as pennies in the DB.
    int CUSTOMER1_MAIN_BALANCE_IN_PENNIES = 0;
    double CUSTOMER1_OVERDRAFT_BALANCE = 123.45;
    int CUSTOMER1_OVERDRAFT_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_OVERDRAFT_BALANCE);
    int CUSTOMER1_NUM_FRAUD_REVERSALS = 0;
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER1_ID, CUSTOMER1_PASSWORD, CUSTOMER1_FIRST_NAME, CUSTOMER1_LAST_NAME, CUSTOMER1_MAIN_BALANCE_IN_PENNIES, 0, CUSTOMER1_OVERDRAFT_BALANCE_IN_PENNIES, 0, CUSTOMER1_NUM_FRAUD_REVERSALS, 0);

    // Prepare Deposit Form to Deposit $50 to customer 1's account.
    double CUSTOMER1_AMOUNT_TO_DEPOSIT = 50; // user input is in dollar amount, not pennies.
    User customer1DepositFormInputs = new User();
    customer1DepositFormInputs.setUsername(CUSTOMER1_ID);
    customer1DepositFormInputs.setPassword(CUSTOMER1_PASSWORD);
    customer1DepositFormInputs.setAmountToDeposit(CUSTOMER1_AMOUNT_TO_DEPOSIT); 

    // store timestamp of when Deposit request is sent to verify timestamps in the TransactionHistory and OverdraftLogs tables later
    LocalDateTime timeWhenDepositRequestSent = MvcControllerIntegTestHelpers.fetchCurrentTimeAsLocalDateTimeNoMilliseconds();
    System.out.println("Timestamp when Deposit Request is sent: " + timeWhenDepositRequestSent);

    // send request to the Deposit Form's POST handler in MvcController
    controller.submitDeposit(ACCOUNT_TYPE_CHECKING, customer1DepositFormInputs);

    // fetch updated data from the DB
    List<Map<String,Object>> customersTableData = jdbcTemplate.queryForList("SELECT * FROM Customers;");
    List<Map<String,Object>> transactionHistoryTableData = jdbcTemplate.queryForList("SELECT * FROM CheckingTransactionHistory;");
    List<Map<String,Object>> overdraftLogsTableData = jdbcTemplate.queryForList("SELECT * FROM CheckingOverdraftLogs;");

    // verify that customer's overdraft balance is now $100
    Map<String,Object> customer1Data = customersTableData.get(0);
    int CUSTOMER1_ORIGINAL_OVERDRAFT_BALANCE_IN_PENNIES = CUSTOMER1_OVERDRAFT_BALANCE_IN_PENNIES;
    int CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_AMOUNT_TO_DEPOSIT);
    int CUSTOMER1_EXPECTED_FINAL_OVERDRAFT_BALANCE_IN_PENNIES = CUSTOMER1_ORIGINAL_OVERDRAFT_BALANCE_IN_PENNIES - CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES;
    assertEquals(CUSTOMER1_EXPECTED_FINAL_OVERDRAFT_BALANCE_IN_PENNIES, (int)customer1Data.get("CheckingOverdraftBalance"));

    // verify that the customer's main balance is still $0
    int CUSTOMER1_EXPECTED_MAIN_BALANCE_IN_PENNIES = 0;
    assertEquals(CUSTOMER1_EXPECTED_MAIN_BALANCE_IN_PENNIES, (int)customer1Data.get("CheckingBalance"));

    // verify that the deposit is logged properly in the OverdraftLogs table
    Map<String,Object> customer1OverdraftLog = overdraftLogsTableData.get(0);
    MvcControllerIntegTestHelpers.checkOverdraftLog(customer1OverdraftLog, timeWhenDepositRequestSent, CUSTOMER1_ID, CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES, CUSTOMER1_ORIGINAL_OVERDRAFT_BALANCE_IN_PENNIES, CUSTOMER1_EXPECTED_FINAL_OVERDRAFT_BALANCE_IN_PENNIES);

    // verify that the Withdraw's details are accurately logged in the TransactionHistory table
    Map<String,Object> customer1TransactionLog = transactionHistoryTableData.get(0);
    MvcControllerIntegTestHelpers.checkTransactionLog(customer1TransactionLog, timeWhenDepositRequestSent, CUSTOMER1_ID, MvcController.TRANSACTION_HISTORY_DEPOSIT_ACTION, CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES);
  }

    /**
   * Verifies the case where a customer is in overdraft in the savings account 
   * and deposits an amount that still leaves some leftover SavingsOverdraft balance.
   * The customer's SavingsOverdraftBalance in the Customers table should be 
   * set to $0, and their main SavingsBalance should still be $0 in the MySQL DB.
   * 
   * This Deposit should be logged in the SavingsOverdraftLogs table since it is a repayment.
   * 
   * This Deposit should still be recorded in the SavingsTransactionHistory table.
   * 
   * A few Assertions are omitted to remove clutter since they are already
   * checked in detail in testSimpleDeposit().
   * 
   * @throws SQLException
   * @throws ScriptException
   */
  @Test
  public void testSavingsDepositOverdraftNotCleared() throws SQLException, ScriptException {
    // initialize customer1 with an overdraft balance of $123.45 (to make sure this works for non-whole dollar amounts). represented as pennies in the DB.
    int CUSTOMER1_MAIN_BALANCE_IN_PENNIES = 0;
    double CUSTOMER1_OVERDRAFT_BALANCE = 123.45;
    int CUSTOMER1_OVERDRAFT_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_OVERDRAFT_BALANCE);
    int CUSTOMER1_NUM_FRAUD_REVERSALS = 0;
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER1_ID, CUSTOMER1_PASSWORD, CUSTOMER1_FIRST_NAME, CUSTOMER1_LAST_NAME, 0, CUSTOMER1_MAIN_BALANCE_IN_PENNIES, 0, CUSTOMER1_OVERDRAFT_BALANCE_IN_PENNIES, CUSTOMER1_NUM_FRAUD_REVERSALS, 0);

    // Prepare Deposit Form to Deposit $50 to customer 1's account.
    double CUSTOMER1_AMOUNT_TO_DEPOSIT = 50; // user input is in dollar amount, not pennies.
    User customer1DepositFormInputs = new User();
    customer1DepositFormInputs.setUsername(CUSTOMER1_ID);
    customer1DepositFormInputs.setPassword(CUSTOMER1_PASSWORD);
    customer1DepositFormInputs.setAmountToDeposit(CUSTOMER1_AMOUNT_TO_DEPOSIT); 

    // store timestamp of when Deposit request is sent to verify timestamps in the TransactionHistory and OverdraftLogs tables later
    LocalDateTime timeWhenDepositRequestSent = MvcControllerIntegTestHelpers.fetchCurrentTimeAsLocalDateTimeNoMilliseconds();
    System.out.println("Timestamp when Deposit Request is sent: " + timeWhenDepositRequestSent);

    // send request to the Deposit Form's POST handler in MvcController
    controller.submitDeposit(ACCOUNT_TYPE_SAVINGS, customer1DepositFormInputs);

    // fetch updated data from the DB
    List<Map<String,Object>> customersTableData = jdbcTemplate.queryForList("SELECT * FROM Customers;");
    List<Map<String,Object>> transactionHistoryTableData = jdbcTemplate.queryForList("SELECT * FROM SavingsTransactionHistory;");
    List<Map<String,Object>> overdraftLogsTableData = jdbcTemplate.queryForList("SELECT * FROM SavingsOverdraftLogs;");

    // verify that customer's overdraft balance is now $100
    Map<String,Object> customer1Data = customersTableData.get(0);
    int CUSTOMER1_ORIGINAL_OVERDRAFT_BALANCE_IN_PENNIES = CUSTOMER1_OVERDRAFT_BALANCE_IN_PENNIES;
    int CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_AMOUNT_TO_DEPOSIT);
    int CUSTOMER1_EXPECTED_FINAL_OVERDRAFT_BALANCE_IN_PENNIES = CUSTOMER1_ORIGINAL_OVERDRAFT_BALANCE_IN_PENNIES - CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES;
    assertEquals(CUSTOMER1_EXPECTED_FINAL_OVERDRAFT_BALANCE_IN_PENNIES, (int)customer1Data.get("SavingsOverdraftBalance"));

    // verify that the customer's main balance is still $0
    int CUSTOMER1_EXPECTED_MAIN_BALANCE_IN_PENNIES = 0;
    assertEquals(CUSTOMER1_EXPECTED_MAIN_BALANCE_IN_PENNIES, (int)customer1Data.get("SavingsBalance"));

    // verify that the deposit is logged properly in the OverdraftLogs table
    Map<String,Object> customer1OverdraftLog = overdraftLogsTableData.get(0);
    MvcControllerIntegTestHelpers.checkOverdraftLog(customer1OverdraftLog, timeWhenDepositRequestSent, CUSTOMER1_ID, CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES, CUSTOMER1_ORIGINAL_OVERDRAFT_BALANCE_IN_PENNIES, CUSTOMER1_EXPECTED_FINAL_OVERDRAFT_BALANCE_IN_PENNIES);

    // verify that the Withdraw's details are accurately logged in the TransactionHistory table
    Map<String,Object> customer1TransactionLog = transactionHistoryTableData.get(0);
    MvcControllerIntegTestHelpers.checkTransactionLog(customer1TransactionLog, timeWhenDepositRequestSent, CUSTOMER1_ID, MvcController.TRANSACTION_HISTORY_DEPOSIT_ACTION, CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES);
  }

  /**
   * Verifies the transaction dispute feature on a simple deposit transaction 
   * for the checking account. The customer's main checking balance should 
   * go back to the original value after the reversal of the deposit.
   * The customer's numFraudReversals counter should also be incremented by 1.
   * 
   * The initial Deposit should be recorded in the CheckingTransactionHistory table.
   * 
   * The reversed Deposit should be recorded in the CheckingTransactionHistory table
   * as a Withdraw.
   * 
   * Some verifications are not done on the initial Deposit since it is already
   * checked in detail in testSimpleDepositChecking().
   * 
   * @throws SQLException
   * @throws ScriptException
   * @throws InterruptedException
   */
  @Test
  public void testReversalOfSimpleDepositChecking() throws SQLException, ScriptException, InterruptedException {
    // initialize customer1 with a balance of $123.45 (to make sure this works for non-whole dollar amounts). represented as pennies in the DB.
    // No overdraft or numFraudReversals.
    double CUSTOMER1_BALANCE = 123.45;
    int CUSTOMER1_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_BALANCE);
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER1_ID, CUSTOMER1_PASSWORD, CUSTOMER1_FIRST_NAME, CUSTOMER1_LAST_NAME, CUSTOMER1_BALANCE_IN_PENNIES, 0, 0);

    // Prepare Deposit Form to Deposit $12.34 (to make sure this works for non-whole dollar amounts) to customer 1's account.
    double CUSTOMER1_AMOUNT_TO_DEPOSIT = 12.34; // user input is in dollar amount, not pennies.
    User customer1DepositFormInputs = new User();
    customer1DepositFormInputs.setUsername(CUSTOMER1_ID);
    customer1DepositFormInputs.setPassword(CUSTOMER1_PASSWORD);
    customer1DepositFormInputs.setAmountToDeposit(CUSTOMER1_AMOUNT_TO_DEPOSIT);

    // store timestamp of when Deposit request is sent to verify timestamps in the TransactionHistory table later
    LocalDateTime timeWhenDepositRequestSent = MvcControllerIntegTestHelpers.fetchCurrentTimeAsLocalDateTimeNoMilliseconds();
    System.out.println("Timestamp when Deposit Request is sent: " + timeWhenDepositRequestSent);

    // send request to the Deposit Form's POST handler in MvcController
    controller.submitDeposit(ACCOUNT_TYPE_CHECKING, customer1DepositFormInputs);

    // verify customer1's balance after the deposit
    List<Map<String,Object>> customersTableData = jdbcTemplate.queryForList("SELECT * FROM Customers;");
    Map<String,Object> customer1Data = customersTableData.get(0);
    double CUSTOMER1_EXPECTED_BALANCE_AFTER_DEPOSIT = CUSTOMER1_BALANCE + CUSTOMER1_AMOUNT_TO_DEPOSIT; 
    int CUSTOMER1_EXPECTED_BALANCE_AFTER_DEPOSIT_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_EXPECTED_BALANCE_AFTER_DEPOSIT);
    assertEquals(CUSTOMER1_EXPECTED_BALANCE_AFTER_DEPOSIT_IN_PENNIES, (int)customer1Data.get("CheckingBalance"));

    // sleep for 1 second to ensure the timestamps of Deposit and Reversal are different (and sortable) in TransactionHistory table
    Thread.sleep(1000);

    // Prepare Reversal Form to reverse the Deposit
    User customer1ReversalFormInputs = customer1DepositFormInputs;
    customer1ReversalFormInputs.setNumTransactionsAgo(1); // reverse the most recent transaction

    // store timestamp of when Reversal request is sent to verify timestamps in the TransactionHistory table later
    LocalDateTime timeWhenReversalRequestSent = MvcControllerIntegTestHelpers.fetchCurrentTimeAsLocalDateTimeNoMilliseconds();
    System.out.println("Timestamp when Reversal Request is sent: " + timeWhenReversalRequestSent);

    // send Dispute request
    controller.submitDispute(ACCOUNT_TYPE_CHECKING, customer1ReversalFormInputs);

    // re-fetch updated customer data from the DB
    customersTableData = jdbcTemplate.queryForList("SELECT * FROM Customers;");
    customer1Data = customersTableData.get(0);

    // verify that customer1's balance is back to the original value
    int CUSTOMER1_EXPECTED_BALANCE_AFTER_REVERSAL_IN_PENNIES = CUSTOMER1_BALANCE_IN_PENNIES;
    assertEquals(CUSTOMER1_EXPECTED_BALANCE_AFTER_REVERSAL_IN_PENNIES, (int)customer1Data.get("CheckingBalance"));

    // verify that customer1's numFraudReversals counter is now 1
    assertEquals(1, (int) customer1Data.get("NumFraudReversals"));

    // fetch transaction data from the DB in chronological order
    // the more recent transaction should be the Reversal, and the older transaction should be the Deposit
    List<Map<String,Object>> transactionHistoryTableData = jdbcTemplate.queryForList("SELECT * FROM CheckingTransactionHistory ORDER BY Timestamp ASC;");
    Map<String,Object> customer1DepositTransactionLog = transactionHistoryTableData.get(0);
    Map<String,Object> customer1ReversalTransactionLog = transactionHistoryTableData.get(1);

    // verify that the Deposit's details are accurately logged in the TransactionHistory table
    int CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_AMOUNT_TO_DEPOSIT);
    MvcControllerIntegTestHelpers.checkTransactionLog(customer1DepositTransactionLog, timeWhenDepositRequestSent, CUSTOMER1_ID, MvcController.TRANSACTION_HISTORY_DEPOSIT_ACTION, CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES);

    // verify that the Reversal is accurately logged in the TransactionHistory table as a Withdraw
    int CUSTOMER1_AMOUNT_TO_WITHDRAW_IN_PENNIES = CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES;
    MvcControllerIntegTestHelpers.checkTransactionLog(customer1ReversalTransactionLog, timeWhenReversalRequestSent, CUSTOMER1_ID, MvcController.TRANSACTION_HISTORY_WITHDRAW_ACTION, CUSTOMER1_AMOUNT_TO_WITHDRAW_IN_PENNIES);
  }

  /**
   * Verifies the transaction dispute feature on a simple deposit transaction 
   * for the savings account. The customer's main savings balance should 
   * go back to the original value after the reversal of the deposit.
   * The customer's numFraudReversals counter should also be incremented by 1.
   * 
   * The initial Deposit should be recorded in the SavingsTransactionHistory table.
   * 
   * The reversed Deposit should be recorded in the SavingsTransactionHistory table
   * as a Withdraw.
   * 
   * Some verifications are not done on the initial Deposit since it is already
   * checked in detail in testSimpleDepositSavings().
   * 
   * @throws SQLException
   * @throws ScriptException
   * @throws InterruptedException
   */
  @Test
  public void testReversalOfSimpleDepositSavings() throws SQLException, ScriptException, InterruptedException {
    // initialize customer1 with a balance of $123.45 (to make sure this works for non-whole dollar amounts). represented as pennies in the DB.
    // No overdraft or numFraudReversals.
    double CUSTOMER1_BALANCE = 123.45;
    int CUSTOMER1_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_BALANCE);
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER1_ID, CUSTOMER1_PASSWORD, CUSTOMER1_FIRST_NAME, CUSTOMER1_LAST_NAME, 0, CUSTOMER1_BALANCE_IN_PENNIES, 0);

    // Prepare Deposit Form to Deposit $12.34 (to make sure this works for non-whole dollar amounts) to customer 1's account.
    double CUSTOMER1_AMOUNT_TO_DEPOSIT = 12.34; // user input is in dollar amount, not pennies.
    User customer1DepositFormInputs = new User();
    customer1DepositFormInputs.setUsername(CUSTOMER1_ID);
    customer1DepositFormInputs.setPassword(CUSTOMER1_PASSWORD);
    customer1DepositFormInputs.setAmountToDeposit(CUSTOMER1_AMOUNT_TO_DEPOSIT);

    // store timestamp of when Deposit request is sent to verify timestamps in the TransactionHistory table later
    LocalDateTime timeWhenDepositRequestSent = MvcControllerIntegTestHelpers.fetchCurrentTimeAsLocalDateTimeNoMilliseconds();
    System.out.println("Timestamp when Deposit Request is sent: " + timeWhenDepositRequestSent);

    // send request to the Deposit Form's POST handler in MvcController
    controller.submitDeposit(ACCOUNT_TYPE_SAVINGS, customer1DepositFormInputs);

    // verify customer1's balance after the deposit
    List<Map<String,Object>> customersTableData = jdbcTemplate.queryForList("SELECT * FROM Customers;");
    Map<String,Object> customer1Data = customersTableData.get(0);
    double CUSTOMER1_EXPECTED_BALANCE_AFTER_DEPOSIT = CUSTOMER1_BALANCE + CUSTOMER1_AMOUNT_TO_DEPOSIT; 
    int CUSTOMER1_EXPECTED_BALANCE_AFTER_DEPOSIT_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_EXPECTED_BALANCE_AFTER_DEPOSIT);
    assertEquals(CUSTOMER1_EXPECTED_BALANCE_AFTER_DEPOSIT_IN_PENNIES, (int)customer1Data.get("SavingsBalance"));

    // sleep for 1 second to ensure the timestamps of Deposit and Reversal are different (and sortable) in TransactionHistory table
    Thread.sleep(1000);

    // Prepare Reversal Form to reverse the Deposit
    User customer1ReversalFormInputs = customer1DepositFormInputs;
    customer1ReversalFormInputs.setNumTransactionsAgo(1); // reverse the most recent transaction

    // store timestamp of when Reversal request is sent to verify timestamps in the TransactionHistory table later
    LocalDateTime timeWhenReversalRequestSent = MvcControllerIntegTestHelpers.fetchCurrentTimeAsLocalDateTimeNoMilliseconds();
    System.out.println("Timestamp when Reversal Request is sent: " + timeWhenReversalRequestSent);

    // send Dispute request
    controller.submitDispute(ACCOUNT_TYPE_SAVINGS, customer1ReversalFormInputs);

    // re-fetch updated customer data from the DB
    customersTableData = jdbcTemplate.queryForList("SELECT * FROM Customers;");
    customer1Data = customersTableData.get(0);

    // verify that customer1's balance is back to the original value
    int CUSTOMER1_EXPECTED_BALANCE_AFTER_REVERSAL_IN_PENNIES = CUSTOMER1_BALANCE_IN_PENNIES;
    assertEquals(CUSTOMER1_EXPECTED_BALANCE_AFTER_REVERSAL_IN_PENNIES, (int)customer1Data.get("SavingsBalance"));

    // verify that customer1's numFraudReversals counter is now 1
    assertEquals(1, (int) customer1Data.get("NumFraudReversals"));

    // fetch transaction data from the DB in chronological order
    // the more recent transaction should be the Reversal, and the older transaction should be the Deposit
    List<Map<String,Object>> transactionHistoryTableData = jdbcTemplate.queryForList("SELECT * FROM SavingsTransactionHistory ORDER BY Timestamp ASC;");
    Map<String,Object> customer1DepositTransactionLog = transactionHistoryTableData.get(0);
    Map<String,Object> customer1ReversalTransactionLog = transactionHistoryTableData.get(1);

    // verify that the Deposit's details are accurately logged in the TransactionHistory table
    int CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_AMOUNT_TO_DEPOSIT);
    MvcControllerIntegTestHelpers.checkTransactionLog(customer1DepositTransactionLog, timeWhenDepositRequestSent, CUSTOMER1_ID, MvcController.TRANSACTION_HISTORY_DEPOSIT_ACTION, CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES);

    // verify that the Reversal is accurately logged in the TransactionHistory table as a Withdraw
    int CUSTOMER1_AMOUNT_TO_WITHDRAW_IN_PENNIES = CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES;
    MvcControllerIntegTestHelpers.checkTransactionLog(customer1ReversalTransactionLog, timeWhenReversalRequestSent, CUSTOMER1_ID, MvcController.TRANSACTION_HISTORY_WITHDRAW_ACTION, CUSTOMER1_AMOUNT_TO_WITHDRAW_IN_PENNIES);
  }

  /**
   * Verifies the transaction dispute feature on a simple withdraw transaction for
   * the checking account. The customer's main checking balance should go back 
   * to the original value after the reversal of the withdraw. The customer's 
   * numFraudReversals counter should also be incremented by 1.
   * 
   * The initial Withdraw should be recorded in the CheckingTransactionHistory table.
   * 
   * The reversed Withdraw should be recorded in the CheckingTransactionHistory table
   * as a Deposit.
   * 
   * Some verifications are not done on the initial Withdraw since it is already
   * checked in detail in testSimpleWithdrawChecking().
   * 
   * @throws SQLException
   * @throws ScriptException
   * @throws InterruptedException
   */
  @Test
  public void testReversalOfSimpleWithdrawChecking() throws SQLException, ScriptException, InterruptedException {
    // initialize customer1 with a checking balance of $123.45 (to make sure this works for non-whole dollar amounts). represented as pennies in the DB.
    // No overdraft or numFraudReversals.
    double CUSTOMER1_BALANCE = 123.45;
    int CUSTOMER1_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_BALANCE);
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER1_ID, CUSTOMER1_PASSWORD, CUSTOMER1_FIRST_NAME, CUSTOMER1_LAST_NAME, CUSTOMER1_BALANCE_IN_PENNIES, 0, 0);

    // Prepare Withdraw Form to Withdraw $12.34 to customer 1's account.
    double CUSTOMER1_AMOUNT_TO_WITHDRAW = 12.34; // user input is in dollar amount, not pennies.
    User customer1WithdrawFormInputs = new User();
    customer1WithdrawFormInputs.setUsername(CUSTOMER1_ID);
    customer1WithdrawFormInputs.setPassword(CUSTOMER1_PASSWORD);
    customer1WithdrawFormInputs.setAmountToWithdraw(CUSTOMER1_AMOUNT_TO_WITHDRAW);

    // store timestamp of when Withdraw request is sent to verify timestamps in the TransactionHistory table later
    LocalDateTime timeWhenWithdrawRequestSent = MvcControllerIntegTestHelpers.fetchCurrentTimeAsLocalDateTimeNoMilliseconds();
    System.out.println("Timestamp when Withdraw Request is sent: " + timeWhenWithdrawRequestSent);

    // send request to the Withdraw Form's POST handler in MvcController
    controller.submitWithdraw(ACCOUNT_TYPE_CHECKING, customer1WithdrawFormInputs);

    // verify customer1's balance after the withdraw
    List<Map<String,Object>> customersTableData = jdbcTemplate.queryForList("SELECT * FROM Customers;");
    Map<String,Object> customer1Data = customersTableData.get(0);
    double CUSTOMER1_EXPECTED_BALANCE_AFTER_WITHDRAW = CUSTOMER1_BALANCE - CUSTOMER1_AMOUNT_TO_WITHDRAW;
    int CUSTOMER1_EXPECTED_BALANCE_AFTER_WITHDRAW_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_EXPECTED_BALANCE_AFTER_WITHDRAW);
    assertEquals(CUSTOMER1_EXPECTED_BALANCE_AFTER_WITHDRAW_IN_PENNIES, (int)customer1Data.get("CheckingBalance"));

    // sleep for 1 second to ensure the timestamps of Withdraw and Reversal are different (and sortable) in TransactionHistory table
    Thread.sleep(1000);

    // Prepare Reversal Form to reverse the Withdraw
    User customer1ReversalFormInputs = customer1WithdrawFormInputs;
    customer1ReversalFormInputs.setNumTransactionsAgo(1); // reverse the most recent transaction

    // store timestamp of when Reversal request is sent to verify timestamps in the TransactionHistory table later
    LocalDateTime timeWhenReversalRequestSent = MvcControllerIntegTestHelpers.fetchCurrentTimeAsLocalDateTimeNoMilliseconds();
    System.out.println("Timestamp when Reversal Request is sent: " + timeWhenReversalRequestSent);

    // send Dispute request
    controller.submitDispute(ACCOUNT_TYPE_CHECKING, customer1ReversalFormInputs);

    // re-fetch updated customer data from the DB
    customersTableData = jdbcTemplate.queryForList("SELECT * FROM Customers;");
    customer1Data = customersTableData.get(0);

    // verify that customer1's balance is back to the original value
    int CUSTOMER1_EXPECTED_BALANCE_AFTER_REVERSAL_IN_PENNIES = CUSTOMER1_BALANCE_IN_PENNIES;
    assertEquals(CUSTOMER1_EXPECTED_BALANCE_AFTER_REVERSAL_IN_PENNIES, (int)customer1Data.get("CheckingBalance"));

    // verify that customer1's numFraudReversals counter is now 1
    assertEquals(1, (int) customer1Data.get("NumFraudReversals"));

    // fetch transaction data from the DB in chronological order
    // the more recent transaction should be the Reversal, and the older transaction should be the Withdraw
    List<Map<String,Object>> transactionHistoryTableData = jdbcTemplate.queryForList("SELECT * FROM CheckingTransactionHistory ORDER BY Timestamp ASC;");
    Map<String,Object> customer1WithdrawTransactionLog = transactionHistoryTableData.get(0);
    Map<String,Object> customer1ReversalTransactionLog = transactionHistoryTableData.get(1);

    // verify that the Withdraw's details are accurately logged in the TransactionHistory table
    int CUSTOMER1_AMOUNT_TO_WITHDRAW_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_AMOUNT_TO_WITHDRAW);
    MvcControllerIntegTestHelpers.checkTransactionLog(customer1WithdrawTransactionLog, timeWhenWithdrawRequestSent, CUSTOMER1_ID, MvcController.TRANSACTION_HISTORY_WITHDRAW_ACTION, CUSTOMER1_AMOUNT_TO_WITHDRAW_IN_PENNIES);

    // verify that the Reversal is accurately logged in the TransactionHistory table as a Deposit
    int CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES = CUSTOMER1_AMOUNT_TO_WITHDRAW_IN_PENNIES;
    MvcControllerIntegTestHelpers.checkTransactionLog(customer1ReversalTransactionLog, timeWhenReversalRequestSent, CUSTOMER1_ID, MvcController.TRANSACTION_HISTORY_DEPOSIT_ACTION, CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES);
  }

    /**
   * Verifies the transaction dispute feature on a simple withdraw transaction for
   * the savings account. The customer's main savings balance should go back 
   * to the original value after the reversal of the withdraw. The customer's 
   * numFraudReversals counter should also be incremented by 1.
   * 
   * The initial Withdraw should be recorded in the SavingsTransactionHistory table.
   * 
   * The reversed Withdraw should be recorded in the SavingsTransactionHistory table
   * as a Deposit.
   * 
   * Some verifications are not done on the initial Withdraw since it is already
   * checked in detail in testSimpleWithdrawChecking().
   * 
   * @throws SQLException
   * @throws ScriptException
   * @throws InterruptedException
   */
  @Test
  public void testReversalOfSimpleWithdrawSavings() throws SQLException, ScriptException, InterruptedException {
    // initialize customer1 with a checking balance of $123.45 (to make sure this works for non-whole dollar amounts). represented as pennies in the DB.
    // No overdraft or numFraudReversals.
    double CUSTOMER1_BALANCE = 123.45;
    int CUSTOMER1_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_BALANCE);
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER1_ID, CUSTOMER1_PASSWORD, CUSTOMER1_FIRST_NAME, CUSTOMER1_LAST_NAME, 0, CUSTOMER1_BALANCE_IN_PENNIES, 0);

    // Prepare Withdraw Form to Withdraw $12.34 to customer 1's account.
    double CUSTOMER1_AMOUNT_TO_WITHDRAW = 12.34; // user input is in dollar amount, not pennies.
    User customer1WithdrawFormInputs = new User();
    customer1WithdrawFormInputs.setUsername(CUSTOMER1_ID);
    customer1WithdrawFormInputs.setPassword(CUSTOMER1_PASSWORD);
    customer1WithdrawFormInputs.setAmountToWithdraw(CUSTOMER1_AMOUNT_TO_WITHDRAW);

    // store timestamp of when Withdraw request is sent to verify timestamps in the TransactionHistory table later
    LocalDateTime timeWhenWithdrawRequestSent = MvcControllerIntegTestHelpers.fetchCurrentTimeAsLocalDateTimeNoMilliseconds();
    System.out.println("Timestamp when Withdraw Request is sent: " + timeWhenWithdrawRequestSent);

    // send request to the Withdraw Form's POST handler in MvcController
    controller.submitWithdraw(ACCOUNT_TYPE_SAVINGS, customer1WithdrawFormInputs);

    // verify customer1's balance after the withdraw
    List<Map<String,Object>> customersTableData = jdbcTemplate.queryForList("SELECT * FROM Customers;");
    Map<String,Object> customer1Data = customersTableData.get(0);
    double CUSTOMER1_EXPECTED_BALANCE_AFTER_WITHDRAW = CUSTOMER1_BALANCE - CUSTOMER1_AMOUNT_TO_WITHDRAW;
    int CUSTOMER1_EXPECTED_BALANCE_AFTER_WITHDRAW_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_EXPECTED_BALANCE_AFTER_WITHDRAW);
    assertEquals(CUSTOMER1_EXPECTED_BALANCE_AFTER_WITHDRAW_IN_PENNIES, (int)customer1Data.get("SavingsBalance"));

    // sleep for 1 second to ensure the timestamps of Withdraw and Reversal are different (and sortable) in TransactionHistory table
    Thread.sleep(1000);

    // Prepare Reversal Form to reverse the Withdraw
    User customer1ReversalFormInputs = customer1WithdrawFormInputs;
    customer1ReversalFormInputs.setNumTransactionsAgo(1); // reverse the most recent transaction

    // store timestamp of when Reversal request is sent to verify timestamps in the TransactionHistory table later
    LocalDateTime timeWhenReversalRequestSent = MvcControllerIntegTestHelpers.fetchCurrentTimeAsLocalDateTimeNoMilliseconds();
    System.out.println("Timestamp when Reversal Request is sent: " + timeWhenReversalRequestSent);

    // send Dispute request
    controller.submitDispute(ACCOUNT_TYPE_SAVINGS, customer1ReversalFormInputs);

    // re-fetch updated customer data from the DB
    customersTableData = jdbcTemplate.queryForList("SELECT * FROM Customers;");
    customer1Data = customersTableData.get(0);

    // verify that customer1's balance is back to the original value
    int CUSTOMER1_EXPECTED_BALANCE_AFTER_REVERSAL_IN_PENNIES = CUSTOMER1_BALANCE_IN_PENNIES;
    assertEquals(CUSTOMER1_EXPECTED_BALANCE_AFTER_REVERSAL_IN_PENNIES, (int)customer1Data.get("SavingsBalance"));

    // verify that customer1's numFraudReversals counter is now 1
    assertEquals(1, (int) customer1Data.get("NumFraudReversals"));

    // fetch transaction data from the DB in chronological order
    // the more recent transaction should be the Reversal, and the older transaction should be the Withdraw
    List<Map<String,Object>> transactionHistoryTableData = jdbcTemplate.queryForList("SELECT * FROM SavingsTransactionHistory ORDER BY Timestamp ASC;");
    Map<String,Object> customer1WithdrawTransactionLog = transactionHistoryTableData.get(0);
    Map<String,Object> customer1ReversalTransactionLog = transactionHistoryTableData.get(1);

    // verify that the Withdraw's details are accurately logged in the TransactionHistory table
    int CUSTOMER1_AMOUNT_TO_WITHDRAW_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_AMOUNT_TO_WITHDRAW);
    MvcControllerIntegTestHelpers.checkTransactionLog(customer1WithdrawTransactionLog, timeWhenWithdrawRequestSent, CUSTOMER1_ID, MvcController.TRANSACTION_HISTORY_WITHDRAW_ACTION, CUSTOMER1_AMOUNT_TO_WITHDRAW_IN_PENNIES);

    // verify that the Reversal is accurately logged in the TransactionHistory table as a Deposit
    int CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES = CUSTOMER1_AMOUNT_TO_WITHDRAW_IN_PENNIES;
    MvcControllerIntegTestHelpers.checkTransactionLog(customer1ReversalTransactionLog, timeWhenReversalRequestSent, CUSTOMER1_ID, MvcController.TRANSACTION_HISTORY_DEPOSIT_ACTION, CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES);
  }

  /**
   * Verifies that a customer's account is "frozen" if they
   * have reached the maximum allowed disputes/reversals. These
   * are reversals from either checking or savings accounts, 
   * both count towards the total number of disputes.
   * 
   * "Frozen" means that any further deposit, withdraw,
   * and dispute requests are ignored and the customer is 
   * simply redirected to the "welcome" page.
   * 
   * The customer should still be able to view their account
   * via the login form.
   * 
   * @throws SQLException
   * @throws ScriptException
   * @throws InterruptedException
   */
  @Test
  public void testFrozenAccountCheckingBalance() throws SQLException, ScriptException, InterruptedException {
    // initialize a customer in the DB that can only do 1 more dispute/reversal.
    int CUSTOMER1_NUM_FRAUD_REVERSALS = MvcController.MAX_DISPUTES - 1;
    // initialize with $100 main balance and $0 overdraft balance for simplicity
    double CUSTOMER1_MAIN_BALANCE = 100;
    int CUSTOMER1_MAIN_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_MAIN_BALANCE);
    int CUSTOMER1_OVERDRAFT_BALANCE_IN_PENNIES = 0;
    int CUSTOMER1_NUM_INTEREST_DEPOSITS = 0;
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, 
                                                  CUSTOMER1_ID, 
                                                  CUSTOMER1_PASSWORD, 
                                                  CUSTOMER1_FIRST_NAME, 
                                                  CUSTOMER1_LAST_NAME, 
                                                  CUSTOMER1_MAIN_BALANCE_IN_PENNIES, 
                                                  CUSTOMER1_MAIN_BALANCE_IN_PENNIES,
                                                  CUSTOMER1_OVERDRAFT_BALANCE_IN_PENNIES,
                                                  CUSTOMER1_OVERDRAFT_BALANCE_IN_PENNIES,
                                                  CUSTOMER1_NUM_FRAUD_REVERSALS,
                                                  CUSTOMER1_NUM_INTEREST_DEPOSITS);

    // Deposit $50, and then immediately dispute/reverse that deposit.
    // this will bring the customer to the MAX_DISPUTES limit, and also have a few
    // transactions in the TransactionHistory table that we can attempt to dispute/reverse
    // later (which we will expect to fail due to MAX_DISPUTES limit, and not due to a lack
    // of transactions to reverse for the customer).
    // The asserts for this deposit & dispute are not very rigorous as they are covered
    // in the testReversalOfSimpleDeposit() test case.
    double CUSTOMER1_AMOUNT_TO_DEPOSIT = 50;
    User customer1DepositFormInputs = new User();
    customer1DepositFormInputs.setUsername(CUSTOMER1_ID);
    customer1DepositFormInputs.setPassword(CUSTOMER1_PASSWORD);
    customer1DepositFormInputs.setAmountToDeposit(CUSTOMER1_AMOUNT_TO_DEPOSIT);

    // send Deposit request to the Deposit Form's POST handler in MvcController
    controller.submitDeposit(ACCOUNT_TYPE_CHECKING, customer1DepositFormInputs);

    // verify customer1's balance after the deposit
    List<Map<String,Object>> customersTableData = jdbcTemplate.queryForList("SELECT * FROM Customers;");
    Map<String,Object> customer1Data = customersTableData.get(0);
    double CUSTOMER1_EXPECTED_BALANCE_AFTER_DEPOSIT = CUSTOMER1_MAIN_BALANCE + CUSTOMER1_AMOUNT_TO_DEPOSIT; 
    int CUSTOMER1_EXPECTED_BALANCE_AFTER_DEPOSIT_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_EXPECTED_BALANCE_AFTER_DEPOSIT);
    assertEquals(CUSTOMER1_EXPECTED_BALANCE_AFTER_DEPOSIT_IN_PENNIES, (int)customer1Data.get("CheckingBalance"));

    // Prepare Reversal Form to reverse the Deposit
    User customer1ReversalFormInputs = customer1DepositFormInputs;
    customer1ReversalFormInputs.setNumTransactionsAgo(1); // reverse the most recent transaction

    // send Dispute request
    controller.submitDispute(ACCOUNT_TYPE_CHECKING, customer1ReversalFormInputs);

    // re-fetch updated customer data from the DB
    customersTableData = jdbcTemplate.queryForList("SELECT * FROM Customers;");
    customer1Data = customersTableData.get(0);

    // verify that customer1's balance is back to the original value
    int CUSTOMER1_EXPECTED_BALANCE_AFTER_REVERSAL_IN_PENNIES = CUSTOMER1_MAIN_BALANCE_IN_PENNIES;
    assertEquals(CUSTOMER1_EXPECTED_BALANCE_AFTER_REVERSAL_IN_PENNIES, (int)customer1Data.get("CheckingBalance"));

    // verify that customer1's numFraudReversals counter is now MAX_DISPUTES
    assertEquals(MvcController.MAX_DISPUTES, (int)customer1Data.get("NumFraudReversals"));

    // verify that there are two transactions in the TransactionHistory table
    // (one for the deposit, one for the reversal of that deposit)
    List<Map<String,Object>> transactionHistoryTableData = jdbcTemplate.queryForList("SELECT * FROM CheckingTransactionHistory;");
    assertEquals(2, transactionHistoryTableData.size());

    //// Begin Frozen Account Checks ////
    User customer1FrozenFormInputs = new User();

    // customer should still be able to view account info with the Login Form
    customer1FrozenFormInputs.setUsername(CUSTOMER1_ID);
    customer1FrozenFormInputs.setPassword(CUSTOMER1_PASSWORD);
    String responsePage = controller.submitLoginForm(customer1FrozenFormInputs);
    assertEquals("account_info", responsePage);

    // customer should not be able to Deposit
    customer1FrozenFormInputs.setAmountToDeposit(MvcControllerIntegTestHelpers.convertDollarsToPennies(50));
    responsePage = controller.submitDeposit(ACCOUNT_TYPE_CHECKING, customer1FrozenFormInputs);
    assertEquals("welcome", responsePage);

    // customer should not be able to Withdraw
    customer1FrozenFormInputs.setAmountToWithdraw(MvcControllerIntegTestHelpers.convertDollarsToPennies(50));
    responsePage = controller.submitWithdraw(ACCOUNT_TYPE_CHECKING, customer1FrozenFormInputs);
    assertEquals("welcome", responsePage);

    // customer should not be able to Dispute/Reverse a Transaction
    customer1FrozenFormInputs.setNumTransactionsAgo(1);
    responsePage = controller.submitDispute(ACCOUNT_TYPE_CHECKING, customer1FrozenFormInputs);
    assertEquals("welcome", responsePage);

    // verify customer's data and # of transactions is unchanged
    // re-fetch updated customer data from the DB
    customersTableData = jdbcTemplate.queryForList("SELECT * FROM Customers;");
    customer1Data = customersTableData.get(0);
    assertEquals(CUSTOMER1_EXPECTED_BALANCE_AFTER_REVERSAL_IN_PENNIES, (int)customer1Data.get("CheckingBalance"));
    assertEquals(MvcController.MAX_DISPUTES, (int)customer1Data.get("NumFraudReversals"));

    transactionHistoryTableData = jdbcTemplate.queryForList("SELECT * FROM CheckingTransactionHistory;");
    assertEquals(2, transactionHistoryTableData.size());
  }

  /**
   * Verifies that a customer's account is "frozen" if they
   * have reached the maximum allowed disputes/reversals. These
   * are reversals from either checking or savings accounts, 
   * both count towards the total number of disputes.
   * 
   * "Frozen" means that any further deposit, withdraw,
   * and dispute requests are ignored and the customer is 
   * simply redirected to the "welcome" page.
   * 
   * The customer should still be able to view their account
   * via the login form.
   * 
   * @throws SQLException
   * @throws ScriptException
   * @throws InterruptedException
   */
  @Test
  public void testFrozenAccountSavingsBalance() throws SQLException, ScriptException, InterruptedException {
    // initialize a customer in the DB that can only do 1 more dispute/reversal.
    int CUSTOMER1_NUM_FRAUD_REVERSALS = MvcController.MAX_DISPUTES - 1;
    // initialize with $100 main balance and $0 overdraft balance for simplicity
    double CUSTOMER1_MAIN_BALANCE = 100;
    int CUSTOMER1_MAIN_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_MAIN_BALANCE);
    int CUSTOMER1_OVERDRAFT_BALANCE_IN_PENNIES = 0;
    int CUSTOMER1_NUM_INTEREST_DEPOSITS = 0;
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, 
                                                  CUSTOMER1_ID, 
                                                  CUSTOMER1_PASSWORD, 
                                                  CUSTOMER1_FIRST_NAME, 
                                                  CUSTOMER1_LAST_NAME, 
                                                  CUSTOMER1_MAIN_BALANCE_IN_PENNIES, 
                                                  CUSTOMER1_MAIN_BALANCE_IN_PENNIES,
                                                  CUSTOMER1_OVERDRAFT_BALANCE_IN_PENNIES,
                                                  CUSTOMER1_OVERDRAFT_BALANCE_IN_PENNIES,
                                                  CUSTOMER1_NUM_FRAUD_REVERSALS,
                                                  CUSTOMER1_NUM_INTEREST_DEPOSITS);

    // Deposit $50, and then immediately dispute/reverse that deposit.
    // this will bring the customer to the MAX_DISPUTES limit, and also have a few
    // transactions in the TransactionHistory table that we can attempt to dispute/reverse
    // later (which we will expect to fail due to MAX_DISPUTES limit, and not due to a lack
    // of transactions to reverse for the customer).
    // The asserts for this deposit & dispute are not very rigorous as they are covered
    // in the testReversalOfSimpleDeposit() test case.
    double CUSTOMER1_AMOUNT_TO_DEPOSIT = 50;
    User customer1DepositFormInputs = new User();
    customer1DepositFormInputs.setUsername(CUSTOMER1_ID);
    customer1DepositFormInputs.setPassword(CUSTOMER1_PASSWORD);
    customer1DepositFormInputs.setAmountToDeposit(CUSTOMER1_AMOUNT_TO_DEPOSIT);

    // send Deposit request to the Deposit Form's POST handler in MvcController
    controller.submitDeposit(ACCOUNT_TYPE_SAVINGS, customer1DepositFormInputs);

    // verify customer1's balance after the deposit
    List<Map<String,Object>> customersTableData = jdbcTemplate.queryForList("SELECT * FROM Customers;");
    Map<String,Object> customer1Data = customersTableData.get(0);
    double CUSTOMER1_EXPECTED_BALANCE_AFTER_DEPOSIT = CUSTOMER1_MAIN_BALANCE + CUSTOMER1_AMOUNT_TO_DEPOSIT; 
    int CUSTOMER1_EXPECTED_BALANCE_AFTER_DEPOSIT_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_EXPECTED_BALANCE_AFTER_DEPOSIT);
    assertEquals(CUSTOMER1_EXPECTED_BALANCE_AFTER_DEPOSIT_IN_PENNIES, (int)customer1Data.get("SavingsBalance"));

    // Prepare Reversal Form to reverse the Deposit
    User customer1ReversalFormInputs = customer1DepositFormInputs;
    customer1ReversalFormInputs.setNumTransactionsAgo(1); // reverse the most recent transaction

    // send Dispute request
    controller.submitDispute(ACCOUNT_TYPE_SAVINGS, customer1ReversalFormInputs);

    // re-fetch updated customer data from the DB
    customersTableData = jdbcTemplate.queryForList("SELECT * FROM Customers;");
    customer1Data = customersTableData.get(0);

    // verify that customer1's balance is back to the original value
    int CUSTOMER1_EXPECTED_BALANCE_AFTER_REVERSAL_IN_PENNIES = CUSTOMER1_MAIN_BALANCE_IN_PENNIES;
    assertEquals(CUSTOMER1_EXPECTED_BALANCE_AFTER_REVERSAL_IN_PENNIES, (int)customer1Data.get("SavingsBalance"));

    // verify that customer1's numFraudReversals counter is now MAX_DISPUTES
    assertEquals(MvcController.MAX_DISPUTES, (int)customer1Data.get("NumFraudReversals"));

    // verify that there are two transactions in the TransactionHistory table
    // (one for the deposit, one for the reversal of that deposit)
    List<Map<String,Object>> transactionHistoryTableData = jdbcTemplate.queryForList("SELECT * FROM SavingsTransactionHistory;");
    assertEquals(2, transactionHistoryTableData.size());

    //// Begin Frozen Account Checks ////
    User customer1FrozenFormInputs = new User();

    // customer should still be able to view account info with the Login Form
    customer1FrozenFormInputs.setUsername(CUSTOMER1_ID);
    customer1FrozenFormInputs.setPassword(CUSTOMER1_PASSWORD);
    String responsePage = controller.submitLoginForm(customer1FrozenFormInputs);
    assertEquals("account_info", responsePage);

    // customer should not be able to Deposit
    customer1FrozenFormInputs.setAmountToDeposit(MvcControllerIntegTestHelpers.convertDollarsToPennies(50));
    responsePage = controller.submitDeposit(ACCOUNT_TYPE_SAVINGS, customer1FrozenFormInputs);
    assertEquals("welcome", responsePage);

    // customer should not be able to Withdraw
    customer1FrozenFormInputs.setAmountToWithdraw(MvcControllerIntegTestHelpers.convertDollarsToPennies(50));
    responsePage = controller.submitWithdraw(ACCOUNT_TYPE_SAVINGS, customer1FrozenFormInputs);
    assertEquals("welcome", responsePage);

    // customer should not be able to Dispute/Reverse a Transaction
    customer1FrozenFormInputs.setNumTransactionsAgo(1);
    responsePage = controller.submitDispute(ACCOUNT_TYPE_SAVINGS, customer1FrozenFormInputs);
    assertEquals("welcome", responsePage);

    // verify customer's data and # of transactions is unchanged
    // re-fetch updated customer data from the DB
    customersTableData = jdbcTemplate.queryForList("SELECT * FROM Customers;");
    customer1Data = customersTableData.get(0);
    assertEquals(CUSTOMER1_EXPECTED_BALANCE_AFTER_REVERSAL_IN_PENNIES, (int)customer1Data.get("SavingsBalance"));
    assertEquals(MvcController.MAX_DISPUTES, (int)customer1Data.get("NumFraudReversals"));

    transactionHistoryTableData = jdbcTemplate.queryForList("SELECT * FROM SavingsTransactionHistory;");
    assertEquals(2, transactionHistoryTableData.size());
  }

  /**
   * Verifies the transaction dispute feature on a reversal of a deposit that 
   * causes a customer to exceed the overdraft limit for the checking account.
   * 
   * The initial Deposit and Withdraw should be recorded in the CheckingTransactionHistory table.
   * 
   * Trying to reverse a deposit that causes the customer to go over the overdraft limit
   * should result in the customer being directed to the welcome screen and not process 
   * the reversal.
   * 
   * @throws SQLException
   * @throws ScriptException
   * @throws InterruptedException
   */
  @Test
  public void testReverseDepositExceedsOverdraftLimitChecking() throws SQLException, ScriptException, InterruptedException {
    // initialize customer1 with a balance of $0 represented as pennies in the DB.
    // No overdraft or numFraudReversals.
    double CUSTOMER1_BALANCE = 0;
    int CUSTOMER1_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_BALANCE);
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER1_ID, CUSTOMER1_PASSWORD, CUSTOMER1_FIRST_NAME, CUSTOMER1_LAST_NAME, CUSTOMER1_BALANCE_IN_PENNIES, 0, 0);

    // Prepare Deposit Form to Deposit $100 to customer 1's account.
    double CUSTOMER1_AMOUNT_TO_DEPOSIT = 100; // user input is in dollar amount, not pennies.
    User customer1DepositFormInputs = new User();
    customer1DepositFormInputs.setUsername(CUSTOMER1_ID);
    customer1DepositFormInputs.setPassword(CUSTOMER1_PASSWORD);
    customer1DepositFormInputs.setAmountToDeposit(CUSTOMER1_AMOUNT_TO_DEPOSIT);

    // store timestamp of when Deposit request is sent to verify timestamps in the CheckingTransactionHistory table later
    LocalDateTime timeWhenDepositRequestSent = MvcControllerIntegTestHelpers.fetchCurrentTimeAsLocalDateTimeNoMilliseconds();
    System.out.println("Timestamp when Deposit Request is sent: " + timeWhenDepositRequestSent);

    // send request to the Deposit Form's POST handler in MvcController
    controller.submitDeposit(ACCOUNT_TYPE_CHECKING, customer1DepositFormInputs);
 
     // sleep for 1 second to ensure the timestamps of Deposit, Withdraw, and Reversal are different (and sortable) in CheckingTransactionHistory table
     Thread.sleep(1000);

    // Prepare Withdraw Form to Withdraw $1050 from customer 1's account.
    double CUSTOMER1_AMOUNT_TO_WITHDRAW = 1050; // user input is in dollar amount, not pennies.
    User customer1WithdrawFormInputs = new User();
    customer1WithdrawFormInputs.setUsername(CUSTOMER1_ID);
    customer1WithdrawFormInputs.setPassword(CUSTOMER1_PASSWORD);
    customer1WithdrawFormInputs.setAmountToWithdraw(CUSTOMER1_AMOUNT_TO_WITHDRAW);

    // store timestamp of when Withdraw request is sent to verify timestamps in the CheckingTransactionHistory table later
    LocalDateTime timeWhenWithdrawRequestSent = MvcControllerIntegTestHelpers.fetchCurrentTimeAsLocalDateTimeNoMilliseconds();
    System.out.println("Timestamp when Withdraw Request is sent: " + timeWhenWithdrawRequestSent);

    // send request to the Withdraw Form's POST handler in MvcController
    controller.submitWithdraw(ACCOUNT_TYPE_CHECKING, customer1WithdrawFormInputs);

    // sleep for 1 second to ensure the timestamps of Deposit, Withdraw, and Reversal are different (and sortable) in CheckingTransactionHistory table
    Thread.sleep(1000);

    // fetch transaction data from the DB in chronological order
    List<Map<String,Object>> transactionHistoryTableData = jdbcTemplate.queryForList("SELECT * FROM CheckingTransactionHistory ORDER BY Timestamp ASC;");

    // verify that the Deposit & Withdraw are the only logs in CheckingTransactionHistory table
    assertEquals(2, transactionHistoryTableData.size());

    // Prepare Reversal Form to reverse the Deposit
    User customer1ReversalFormInputs = customer1DepositFormInputs;
    customer1ReversalFormInputs.setNumTransactionsAgo(2); // reverse the first transaction

    // send Dispute request
    String responsePage = controller.submitDispute(ACCOUNT_TYPE_CHECKING, customer1ReversalFormInputs);
    assertEquals("welcome", responsePage);

    // re-fetch transaction data from the DB in chronological order
    // the more recent transaction should be the Withdraw, and the older transaction should be the Deposit
    transactionHistoryTableData = jdbcTemplate.queryForList("SELECT * FROM CheckingTransactionHistory ORDER BY Timestamp ASC;");

    // verify that the original Deposit & Withdraw are still the only logs in CheckingTransactionHistory table
    assertEquals(2, transactionHistoryTableData.size());

    Map<String,Object> customer1DepositTransactionLog = transactionHistoryTableData.get(0);
    Map<String,Object> customer1WithdrawTransactionLog = transactionHistoryTableData.get(1);

    // verify that the Deposit's details are accurately logged in the CheckingTransactionHistory table
    int CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_AMOUNT_TO_DEPOSIT);
    MvcControllerIntegTestHelpers.checkTransactionLog(customer1DepositTransactionLog, timeWhenDepositRequestSent, CUSTOMER1_ID, MvcController.TRANSACTION_HISTORY_DEPOSIT_ACTION, CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES);

    // verify that the Withdraw's details are accurately logged in the CheckingTransactionHistory table
    int CUSTOMER1_AMOUNT_TO_WITHDRAW_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_AMOUNT_TO_WITHDRAW);
    MvcControllerIntegTestHelpers.checkTransactionLog(customer1WithdrawTransactionLog, timeWhenWithdrawRequestSent, CUSTOMER1_ID, MvcController.TRANSACTION_HISTORY_WITHDRAW_ACTION, CUSTOMER1_AMOUNT_TO_WITHDRAW_IN_PENNIES);
  }

    /**
   * Verifies the transaction dispute feature on a reversal of a deposit that 
   * causes a customer to exceed the overdraft limit for the savings account.
   * 
   * The initial Deposit and Withdraw should be recorded in the SavingsTransactionHistory table.
   * 
   * Trying to reverse a deposit that causes the customer to go over the overdraft limit
   * should result in the customer being directed to the welcome screen and not process 
   * the reversal.
   * 
   * @throws SQLException
   * @throws ScriptException
   * @throws InterruptedException
   */
  @Test
  public void testReverseDepositExceedsOverdraftLimitSavings() throws SQLException, ScriptException, InterruptedException {
    // initialize customer1 with a balance of $0 represented as pennies in the DB.
    // No overdraft or numFraudReversals.
    double CUSTOMER1_BALANCE = 0;
    int CUSTOMER1_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_BALANCE);
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER1_ID, CUSTOMER1_PASSWORD, CUSTOMER1_FIRST_NAME, CUSTOMER1_LAST_NAME, 0, CUSTOMER1_BALANCE_IN_PENNIES, 0);

    // Prepare Deposit Form to Deposit $100 to customer 1's account.
    double CUSTOMER1_AMOUNT_TO_DEPOSIT = 100; // user input is in dollar amount, not pennies.
    User customer1DepositFormInputs = new User();
    customer1DepositFormInputs.setUsername(CUSTOMER1_ID);
    customer1DepositFormInputs.setPassword(CUSTOMER1_PASSWORD);
    customer1DepositFormInputs.setAmountToDeposit(CUSTOMER1_AMOUNT_TO_DEPOSIT);

    // store timestamp of when Deposit request is sent to verify timestamps in the SavingsTransactionHistory table later
    LocalDateTime timeWhenDepositRequestSent = MvcControllerIntegTestHelpers.fetchCurrentTimeAsLocalDateTimeNoMilliseconds();
    System.out.println("Timestamp when Deposit Request is sent: " + timeWhenDepositRequestSent);

    // send request to the Deposit Form's POST handler in MvcController
    controller.submitDeposit(ACCOUNT_TYPE_SAVINGS, customer1DepositFormInputs);
 
     // sleep for 1 second to ensure the timestamps of Deposit, Withdraw, and Reversal are different (and sortable) in SavingsTransactionHistory table
     Thread.sleep(1000);

    // Prepare Withdraw Form to Withdraw $1050 from customer 1's account.
    double CUSTOMER1_AMOUNT_TO_WITHDRAW = 1050; // user input is in dollar amount, not pennies.
    User customer1WithdrawFormInputs = new User();
    customer1WithdrawFormInputs.setUsername(CUSTOMER1_ID);
    customer1WithdrawFormInputs.setPassword(CUSTOMER1_PASSWORD);
    customer1WithdrawFormInputs.setAmountToWithdraw(CUSTOMER1_AMOUNT_TO_WITHDRAW);

    // store timestamp of when Withdraw request is sent to verify timestamps in the SavingsTransactionHistory table later
    LocalDateTime timeWhenWithdrawRequestSent = MvcControllerIntegTestHelpers.fetchCurrentTimeAsLocalDateTimeNoMilliseconds();
    System.out.println("Timestamp when Withdraw Request is sent: " + timeWhenWithdrawRequestSent);

    // send request to the Withdraw Form's POST handler in MvcController
    controller.submitWithdraw(ACCOUNT_TYPE_SAVINGS, customer1WithdrawFormInputs);

    // sleep for 1 second to ensure the timestamps of Deposit, Withdraw, and Reversal are different (and sortable) in SavingsTransactionHistory table
    Thread.sleep(1000);

    // fetch transaction data from the DB in chronological order
    List<Map<String,Object>> transactionHistoryTableData = jdbcTemplate.queryForList("SELECT * FROM SavingsTransactionHistory ORDER BY Timestamp ASC;");

    // verify that the Deposit & Withdraw are the only logs in SavingsTransactionHistory table
    assertEquals(2, transactionHistoryTableData.size());

    // Prepare Reversal Form to reverse the Deposit
    User customer1ReversalFormInputs = customer1DepositFormInputs;
    customer1ReversalFormInputs.setNumTransactionsAgo(2); // reverse the first transaction

    // send Dispute request
    String responsePage = controller.submitDispute(ACCOUNT_TYPE_SAVINGS, customer1ReversalFormInputs);
    assertEquals("welcome", responsePage);

    // re-fetch transaction data from the DB in chronological order
    // the more recent transaction should be the Withdraw, and the older transaction should be the Deposit
    transactionHistoryTableData = jdbcTemplate.queryForList("SELECT * FROM SavingsTransactionHistory ORDER BY Timestamp ASC;");

    // verify that the original Deposit & Withdraw are still the only logs in SavingsTransactionHistory table
    assertEquals(2, transactionHistoryTableData.size());

    Map<String,Object> customer1DepositTransactionLog = transactionHistoryTableData.get(0);
    Map<String,Object> customer1WithdrawTransactionLog = transactionHistoryTableData.get(1);

    // verify that the Deposit's details are accurately logged in the TransactionHistory table
    int CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_AMOUNT_TO_DEPOSIT);
    MvcControllerIntegTestHelpers.checkTransactionLog(customer1DepositTransactionLog, timeWhenDepositRequestSent, CUSTOMER1_ID, MvcController.TRANSACTION_HISTORY_DEPOSIT_ACTION, CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES);

    // verify that the Withdraw's details are accurately logged in the TransactionHistory table
    int CUSTOMER1_AMOUNT_TO_WITHDRAW_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_AMOUNT_TO_WITHDRAW);
    MvcControllerIntegTestHelpers.checkTransactionLog(customer1WithdrawTransactionLog, timeWhenWithdrawRequestSent, CUSTOMER1_ID, MvcController.TRANSACTION_HISTORY_WITHDRAW_ACTION, CUSTOMER1_AMOUNT_TO_WITHDRAW_IN_PENNIES);
  }

  /**
   * Verifies the transaction dispute feature on a reversal of a deposit for the 
   * checking account that causes a customer to fall into overdraft.
   * 
   * 
   * Reversing a deposit that causes a customer to fall into overdraft should 
   * make the customer go into overdraft and apply the 2% interest fee on 
   * the overdraft checking balance.
   * 
   * @throws SQLException
   * @throws ScriptException
   * @throws InterruptedException
   */
  @Test
  public void testReverseDepositCausesOverdraftChecking() throws SQLException, ScriptException, InterruptedException {
    // initialize customer1 with a balance of $0 represented as pennies in the DB.
    // No overdraft or numFraudReversals.
    double CUSTOMER1_BALANCE = 0;
    int CUSTOMER1_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_BALANCE);
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER1_ID, CUSTOMER1_PASSWORD, CUSTOMER1_FIRST_NAME, CUSTOMER1_LAST_NAME, CUSTOMER1_BALANCE_IN_PENNIES, 0, 0);

    // Prepare Deposit Form to Deposit $100 to customer 1's account.
    double CUSTOMER1_AMOUNT_TO_DEPOSIT = 100; // user input is in dollar amount, not pennies.
    User customer1DepositFormInputs = new User();
    customer1DepositFormInputs.setUsername(CUSTOMER1_ID);
    customer1DepositFormInputs.setPassword(CUSTOMER1_PASSWORD);
    customer1DepositFormInputs.setAmountToDeposit(CUSTOMER1_AMOUNT_TO_DEPOSIT);

    // send request to the Deposit Form's POST handler in MvcController
    controller.submitDeposit(ACCOUNT_TYPE_CHECKING, customer1DepositFormInputs);

    // sleep for 1 second to ensure the timestamps of Withdraw and Reversal are different (and sortable) in CheckingTransactionHistory table
    Thread.sleep(1000);

    // Prepare Withdraw Form to Withdraw $50 from customer 1's account.
    double CUSTOMER1_AMOUNT_TO_WITHDRAW = 50; // user input is in dollar amount, not pennies.
    User customer1WithdrawFormInputs = new User();
    customer1WithdrawFormInputs.setUsername(CUSTOMER1_ID);
    customer1WithdrawFormInputs.setPassword(CUSTOMER1_PASSWORD);
    customer1WithdrawFormInputs.setAmountToWithdraw(CUSTOMER1_AMOUNT_TO_WITHDRAW);

    // send request to the Withdraw Form's POST handler in MvcController
    controller.submitWithdraw(ACCOUNT_TYPE_CHECKING, customer1WithdrawFormInputs);

    // sleep for 1 second to ensure the timestamps of Withdraw and Reversal are different (and sortable) in TransactionHistory table
    Thread.sleep(1000);

    // fetch updated customer1 data from the DB
    List<Map<String,Object>> customersTableData = jdbcTemplate.queryForList("SELECT * FROM Customers;");

    // Prepare Reversal Form to reverse the Deposit
    User customer1ReversalFormInputs = customer1DepositFormInputs;
    customer1ReversalFormInputs.setNumTransactionsAgo(2); // reverse the first transaction

    // send Dispute request
    controller.submitDispute(ACCOUNT_TYPE_CHECKING, customer1ReversalFormInputs);

    // fetch updated customer1 data from the DB
    customersTableData = jdbcTemplate.queryForList("SELECT * FROM Customers;");
     
     // verify that customer1's main checking balance is now 0
    Map<String,Object> customer1Data = customersTableData.get(0);
    assertEquals(0, (int)customer1Data.get("CheckingBalance"));
    
    // verify that customer1's CheckingOverdraft balance is equal to the remaining withdraw amount with interest applied
    // (convert to pennies before applying interest rate to avoid floating point roundoff errors when applying the interest rate)
    int CUSTOMER1_ORIGINAL_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_BALANCE);
    int CUSTOMER1_AMOUNT_TO_WITHDRAW_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_AMOUNT_TO_WITHDRAW);
    int CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_AMOUNT_TO_DEPOSIT);
    int CUSTOMER1_AMOUNT_TO_REVERSE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_AMOUNT_TO_DEPOSIT);
    int CUSTOMER1_EXPECTED_OVERDRAFT_BALANCE_BEFORE_INTEREST_IN_PENNIES = CUSTOMER1_ORIGINAL_BALANCE_IN_PENNIES + CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES + CUSTOMER1_AMOUNT_TO_WITHDRAW_IN_PENNIES - CUSTOMER1_AMOUNT_TO_REVERSE_IN_PENNIES;
    int CUSTOMER1_EXPECTED_OVERDRAFT_BALANCE_AFTER_INTEREST_IN_PENNIES = MvcControllerIntegTestHelpers.applyOverdraftInterest(CUSTOMER1_EXPECTED_OVERDRAFT_BALANCE_BEFORE_INTEREST_IN_PENNIES);
    System.out.println("Expected Checking Overdraft Balance in pennies: " + CUSTOMER1_EXPECTED_OVERDRAFT_BALANCE_AFTER_INTEREST_IN_PENNIES);
    assertEquals(CUSTOMER1_EXPECTED_OVERDRAFT_BALANCE_AFTER_INTEREST_IN_PENNIES, (int)customer1Data.get("CheckingOverdraftBalance"));
  }

    /**
   * Verifies the transaction dispute feature on a reversal of a deposit 
   * to the savings account that causes a customer to fall into overdraft.
   * 
   * 
   * Reversing a deposit that causes a customer to fall into overdraft should 
   * make the customer go into overdraft and apply the 2% interest fee on 
   * the overdraft balance.
   * 
   * @throws SQLException
   * @throws ScriptException
   * @throws InterruptedException
   */
  @Test
  public void testReverseDepositCausesOverdraftSavings() throws SQLException, ScriptException, InterruptedException {
    // initialize customer1 with a balance of $0 represented as pennies in the DB.
    // No overdraft or numFraudReversals.
    double CUSTOMER1_BALANCE = 0;
    int CUSTOMER1_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_BALANCE);
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER1_ID, CUSTOMER1_PASSWORD, CUSTOMER1_FIRST_NAME, CUSTOMER1_LAST_NAME, 0, CUSTOMER1_BALANCE_IN_PENNIES, 0);

    // Prepare Deposit Form to Deposit $100 to customer 1's account.
    double CUSTOMER1_AMOUNT_TO_DEPOSIT = 100; // user input is in dollar amount, not pennies.
    User customer1DepositFormInputs = new User();
    customer1DepositFormInputs.setUsername(CUSTOMER1_ID);
    customer1DepositFormInputs.setPassword(CUSTOMER1_PASSWORD);
    customer1DepositFormInputs.setAmountToDeposit(CUSTOMER1_AMOUNT_TO_DEPOSIT);

    // send request to the Deposit Form's POST handler in MvcController
    controller.submitDeposit(ACCOUNT_TYPE_SAVINGS, customer1DepositFormInputs);

    // sleep for 1 second to ensure the timestamps of Withdraw and Reversal are different (and sortable) in SavingsTransactionHistory table
    Thread.sleep(1000);

    // Prepare Withdraw Form to Withdraw $50 from customer 1's account.
    double CUSTOMER1_AMOUNT_TO_WITHDRAW = 50; // user input is in dollar amount, not pennies.
    User customer1WithdrawFormInputs = new User();
    customer1WithdrawFormInputs.setUsername(CUSTOMER1_ID);
    customer1WithdrawFormInputs.setPassword(CUSTOMER1_PASSWORD);
    customer1WithdrawFormInputs.setAmountToWithdraw(CUSTOMER1_AMOUNT_TO_WITHDRAW);

    // send request to the Withdraw Form's POST handler in MvcController
    controller.submitWithdraw(ACCOUNT_TYPE_SAVINGS, customer1WithdrawFormInputs);

    // sleep for 1 second to ensure the timestamps of Withdraw and Reversal are different (and sortable) in SavingsTransactionHistory table
    Thread.sleep(1000);

    // fetch updated customer1 data from the DB
    List<Map<String,Object>> customersTableData = jdbcTemplate.queryForList("SELECT * FROM Customers;");

    // Prepare Reversal Form to reverse the Deposit
    User customer1ReversalFormInputs = customer1DepositFormInputs;
    customer1ReversalFormInputs.setNumTransactionsAgo(2); // reverse the first transaction

    // send Dispute request
    controller.submitDispute(ACCOUNT_TYPE_SAVINGS, customer1ReversalFormInputs);

    // fetch updated customer1 data from the DB
    customersTableData = jdbcTemplate.queryForList("SELECT * FROM Customers;");
     
     // verify that customer1's main savings balance is now 0
    Map<String,Object> customer1Data = customersTableData.get(0);
    assertEquals(0, (int)customer1Data.get("SavingsBalance"));
    
    // verify that customer1's Overdraft balance is equal to the remaining withdraw amount with interest applied
    // (convert to pennies before applying interest rate to avoid floating point roundoff errors when applying the interest rate)
    int CUSTOMER1_ORIGINAL_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_BALANCE);
    int CUSTOMER1_AMOUNT_TO_WITHDRAW_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_AMOUNT_TO_WITHDRAW);
    int CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_AMOUNT_TO_DEPOSIT);
    int CUSTOMER1_AMOUNT_TO_REVERSE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_AMOUNT_TO_DEPOSIT);
    int CUSTOMER1_EXPECTED_OVERDRAFT_BALANCE_BEFORE_INTEREST_IN_PENNIES = CUSTOMER1_ORIGINAL_BALANCE_IN_PENNIES + CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES + CUSTOMER1_AMOUNT_TO_WITHDRAW_IN_PENNIES - CUSTOMER1_AMOUNT_TO_REVERSE_IN_PENNIES;
    int CUSTOMER1_EXPECTED_OVERDRAFT_BALANCE_AFTER_INTEREST_IN_PENNIES = MvcControllerIntegTestHelpers.applyOverdraftInterest(CUSTOMER1_EXPECTED_OVERDRAFT_BALANCE_BEFORE_INTEREST_IN_PENNIES);
    System.out.println("Expected Savings Overdraft Balance in pennies: " + CUSTOMER1_EXPECTED_OVERDRAFT_BALANCE_AFTER_INTEREST_IN_PENNIES);
    assertEquals(CUSTOMER1_EXPECTED_OVERDRAFT_BALANCE_AFTER_INTEREST_IN_PENNIES, (int)customer1Data.get("SavingsOverdraftBalance"));
  }

  /**
   * Verifies the transaction dispute feature on a reversal of a deposit 
   * to the checking account that causes a customer to fall back into overdraft
   * 
   * 
   * Reversing a deposit that causes a customer to fall back into an overdraft balance 
   * greater than 0 should put the customer back into the original overdraft balance. 
   * The 2% interest rate should not be re-applied.
   * 
   * @throws SQLException
   * @throws ScriptException
   * @throws InterruptedException
   */
  @Test
  public void testReverseDepositThatRepaysOverdraftChecking() throws SQLException, ScriptException, InterruptedException {
    // initialize customer1 with a balance of $50 represented as pennies in the DB.
    // No overdraft or numFraudReversals.
    double CUSTOMER1_BALANCE = 0;
    int CUSTOMER1_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_BALANCE);
    int CUSTOMER1_OVERDRAFT_BALANCE_IN_PENNIES = 50;
    int CUSTOMER1_NUM_FRAUD_REVERSALS = 0;
    int CUSTOMER1_NUM_INTEREST_DEPOSITS = 0;
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, 
                                                  CUSTOMER1_ID, 
                                                  CUSTOMER1_PASSWORD, 
                                                  CUSTOMER1_FIRST_NAME, 
                                                  CUSTOMER1_LAST_NAME, 
                                                  CUSTOMER1_BALANCE_IN_PENNIES, 
                                                  0,
                                                  CUSTOMER1_OVERDRAFT_BALANCE_IN_PENNIES,
                                                  0,
                                                  CUSTOMER1_NUM_FRAUD_REVERSALS, 
                                                  CUSTOMER1_NUM_INTEREST_DEPOSITS
                                                  );
    
    // Prepare Deposit Form to Deposit $100 to customer 1's account.
    double CUSTOMER1_AMOUNT_TO_DEPOSIT = 100; // user input is in dollar amount, not pennies.
    User customer1DepositFormInputs = new User();
    customer1DepositFormInputs.setUsername(CUSTOMER1_ID);
    customer1DepositFormInputs.setPassword(CUSTOMER1_PASSWORD);
    customer1DepositFormInputs.setAmountToDeposit(CUSTOMER1_AMOUNT_TO_DEPOSIT);

    // send request to the Deposit Form's POST handler in MvcController
    controller.submitDeposit(ACCOUNT_TYPE_CHECKING, customer1DepositFormInputs);
 
    // sleep for 1 second to ensure the timestamps of Deposit, Withdraw, and Reversal are different (and sortable) in CheckingTransactionHistory table
    Thread.sleep(1000);

    // Prepare Reversal Form to reverse the Deposit
    User customer1ReversalFormInputs = customer1DepositFormInputs;
    customer1ReversalFormInputs.setNumTransactionsAgo(1); // reverse the first transaction

    // send Dispute request
    controller.submitDispute(ACCOUNT_TYPE_CHECKING, customer1ReversalFormInputs);

    // fetch updated customer1 data from the DB
     List<Map<String,Object>> customersTableData = jdbcTemplate.queryForList("SELECT * FROM Customers;");
     customersTableData = jdbcTemplate.queryForList("SELECT * FROM Customers;");
     Map<String,Object> customer1Data = customersTableData.get(0);

    // verfiy that checking overdraft balance does not apply extra 2% interest after dispute
    assertEquals(CUSTOMER1_OVERDRAFT_BALANCE_IN_PENNIES, (int)customer1Data.get("CheckingOverdraftBalance"));
  }

    /**
   * Verifies the transaction dispute feature on a reversal of a deposit 
   * to the savings account that causes a customer to fall back into overdraft
   * 
   * 
   * Reversing a deposit that causes a customer to fall back into an overdraft balance 
   * greater than 0 should put the customer back into the original overdraft balance. 
   * The 2% interest rate should not be re-applied.
   * 
   * @throws SQLException
   * @throws ScriptException
   * @throws InterruptedException
   */
  @Test
  public void testReverseDepositThatRepaysOverdraftSavings() throws SQLException, ScriptException, InterruptedException {
    // initialize customer1 with a balance of $50 represented as pennies in the DB.
    // No overdraft or numFraudReversals.
    double CUSTOMER1_BALANCE = 0;
    int CUSTOMER1_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_BALANCE);
    int CUSTOMER1_OVERDRAFT_BALANCE_IN_PENNIES = 50;
    int CUSTOMER1_NUM_FRAUD_REVERSALS = 0;
    int CUSTOMER1_NUM_INTEREST_DEPOSITS = 0;
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, 
                                                  CUSTOMER1_ID, 
                                                  CUSTOMER1_PASSWORD, 
                                                  CUSTOMER1_FIRST_NAME, 
                                                  CUSTOMER1_LAST_NAME, 
                                                  0,
                                                  CUSTOMER1_BALANCE_IN_PENNIES, 
                                                  0,
                                                  CUSTOMER1_OVERDRAFT_BALANCE_IN_PENNIES,
                                                  CUSTOMER1_NUM_FRAUD_REVERSALS, 
                                                  CUSTOMER1_NUM_INTEREST_DEPOSITS
                                                  );
    
    // Prepare Deposit Form to Deposit $100 to customer 1's account.
    double CUSTOMER1_AMOUNT_TO_DEPOSIT = 100; // user input is in dollar amount, not pennies.
    User customer1DepositFormInputs = new User();
    customer1DepositFormInputs.setUsername(CUSTOMER1_ID);
    customer1DepositFormInputs.setPassword(CUSTOMER1_PASSWORD);
    customer1DepositFormInputs.setAmountToDeposit(CUSTOMER1_AMOUNT_TO_DEPOSIT);

    // send request to the Deposit Form's POST handler in MvcController
    controller.submitDeposit(ACCOUNT_TYPE_SAVINGS, customer1DepositFormInputs);
 
    // sleep for 1 second to ensure the timestamps of Deposit, Withdraw, and Reversal are different (and sortable) in SavingsTransactionHistory table
    Thread.sleep(1000);

    // Prepare Reversal Form to reverse the Deposit
    User customer1ReversalFormInputs = customer1DepositFormInputs;
    customer1ReversalFormInputs.setNumTransactionsAgo(1); // reverse the first transaction

    // send Dispute request
    controller.submitDispute(ACCOUNT_TYPE_SAVINGS, customer1ReversalFormInputs);

    // fetch updated customer1 data from the DB
     List<Map<String,Object>> customersTableData = jdbcTemplate.queryForList("SELECT * FROM Customers;");
     customersTableData = jdbcTemplate.queryForList("SELECT * FROM Customers;");
     Map<String,Object> customer1Data = customersTableData.get(0);

    // verfiy that savings overdraft balance does not apply extra 2% interest after dispute
    assertEquals(CUSTOMER1_OVERDRAFT_BALANCE_IN_PENNIES, (int)customer1Data.get("SavingsOverdraftBalance"));
  }

   /**
   * This test verifies that a simple transfer of $100 from Customer1's checking account to Customer2's savings account
   * will take place. Customer1's checking balance will be initialized to $1000, and Customer2's savings balance will 
   * be $500. The transfer occurs from Customer1's checking account to Customer2's savings  account. 
   * 
   * After a successful transfer, Customer1's checking balance should reflect a $900 balance, and Customer2's savings balance should be $600. 
   * 
   * @throws SQLException
   */
  @Test
  public void testSimpleTransferCheckingToSavings() throws SQLException, ScriptException { 

    //Initialize customer1 with a balance of $1000. Balance will be represented as pennies in DB.
    double CUSTOMER1_BALANCE = 1000;
    int CUSTOMER1_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_BALANCE);
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER1_ID, CUSTOMER1_PASSWORD, CUSTOMER1_FIRST_NAME, CUSTOMER1_LAST_NAME, CUSTOMER1_BALANCE_IN_PENNIES, 0, 0);

    //Initialize customer2 with a balance of $500. Balance will be represented as pennies in DB. 
    double CUSTOMER2_BALANCE = 500;
    int CUSTOMER2_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER2_BALANCE);
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER2_ID, CUSTOMER2_PASSWORD, CUSTOMER2_FIRST_NAME, CUSTOMER2_LAST_NAME, 0, CUSTOMER2_BALANCE_IN_PENNIES, 0);

    //Amount to transfer
    double TRANSFER_AMOUNT = 100;
    int TRANSFER_AMOUNT_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(TRANSFER_AMOUNT);

    //Initializing users for the transfer
    User CUSTOMER1 = new User();
    CUSTOMER1.setUsername(CUSTOMER1_ID);
    CUSTOMER1.setPassword(CUSTOMER1_PASSWORD);
    CUSTOMER1.setTransferRecipientID(CUSTOMER2_ID);
    CUSTOMER1.setAmountToTransfer(TRANSFER_AMOUNT);
    CUSTOMER1.setTransferSenderAccountType(ACCOUNT_TYPE_CHECKING);
    CUSTOMER1.setTransferRecipientAccountType(ACCOUNT_TYPE_SAVINGS);
    
    //Send the transfer request.
    String returnedPage = controller.submitTransfer( CUSTOMER1);
    
    //Fetch customer1 & customer2's data from DB
    List<Map<String, Object>> customer1SqlResult = jdbcTemplate.queryForList(String.format("SELECT * FROM Customers WHERE CustomerID='%s';", CUSTOMER1_ID));
    Map<String, Object> customer1Data = customer1SqlResult.get(0);

    List<Map<String, Object>> customer2SqlResult = jdbcTemplate.queryForList(String.format("SELECT * FROM Customers WHERE CustomerID='%s';", CUSTOMER2_ID));
    Map<String, Object> customer2Data = customer2SqlResult.get(0);
   
    //Verify that customer1's balance decreased by $100. 
    assertEquals((CUSTOMER1_BALANCE_IN_PENNIES - TRANSFER_AMOUNT_IN_PENNIES), (int)customer1Data.get("CheckingBalance"));

    //Verify that customer2's balance increased by $100.
    assertEquals((CUSTOMER2_BALANCE_IN_PENNIES + TRANSFER_AMOUNT_IN_PENNIES), (int)customer2Data.get("SavingsBalance"));

    //Check that transfer request goes through.
    assertEquals("account_info", returnedPage);
  }

   /**
   * This test verifies that a simple transfer of $100 from Customer1's savings account to Customer2's checking account
   * will take place. Customer1's savings balance will be initialized to $1000, and Customer2's checking balance will 
   * be $500. The transfer occurs from Customer1's savings account to Customer2's checking account. 
   * 
   * After a successful transfer, Customer1's savings balance should reflect a $900 balance, and Customer2's checking balance should be $600. 
   * 
   * @throws SQLException
   */
  @Test
  public void testSimpleTransferSavingsToChecking() throws SQLException, ScriptException { 

    //Initialize customer1 with a balance of $1000. Balance will be represented as pennies in DB.
    double CUSTOMER1_BALANCE = 1000;
    int CUSTOMER1_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_BALANCE);
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER1_ID, CUSTOMER1_PASSWORD, CUSTOMER1_FIRST_NAME, CUSTOMER1_LAST_NAME, 0, CUSTOMER1_BALANCE_IN_PENNIES, 0);

    //Initialize customer2 with a balance of $500. Balance will be represented as pennies in DB. 
    double CUSTOMER2_BALANCE = 500;
    int CUSTOMER2_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER2_BALANCE);
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER2_ID, CUSTOMER2_PASSWORD, CUSTOMER2_FIRST_NAME, CUSTOMER2_LAST_NAME, CUSTOMER2_BALANCE_IN_PENNIES, 0,  0);

    //Amount to transfer
    double TRANSFER_AMOUNT = 100;
    int TRANSFER_AMOUNT_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(TRANSFER_AMOUNT);

    //Initializing users for the transfer
    User CUSTOMER1 = new User();
    CUSTOMER1.setUsername(CUSTOMER1_ID);
    CUSTOMER1.setPassword(CUSTOMER1_PASSWORD);
    CUSTOMER1.setTransferRecipientID(CUSTOMER2_ID);
    CUSTOMER1.setAmountToTransfer(TRANSFER_AMOUNT);
    CUSTOMER1.setTransferSenderAccountType(ACCOUNT_TYPE_SAVINGS);
    CUSTOMER1.setTransferRecipientAccountType(ACCOUNT_TYPE_CHECKING);
    
    //Send the transfer request.
    String returnedPage = controller.submitTransfer(CUSTOMER1);
    
    //Fetch customer1 & customer2's data from DB
    List<Map<String, Object>> customer1SqlResult = jdbcTemplate.queryForList(String.format("SELECT * FROM Customers WHERE CustomerID='%s';", CUSTOMER1_ID));
    Map<String, Object> customer1Data = customer1SqlResult.get(0);

    List<Map<String, Object>> customer2SqlResult = jdbcTemplate.queryForList(String.format("SELECT * FROM Customers WHERE CustomerID='%s';", CUSTOMER2_ID));
    Map<String, Object> customer2Data = customer2SqlResult.get(0);
   
    //Verify that customer1's balance decreased by $100. 
    assertEquals((CUSTOMER1_BALANCE_IN_PENNIES - TRANSFER_AMOUNT_IN_PENNIES), (int)customer1Data.get("SavingsBalance"));

    //Verify that customer2's balance increased by $100.
    assertEquals((CUSTOMER2_BALANCE_IN_PENNIES + TRANSFER_AMOUNT_IN_PENNIES), (int)customer2Data.get("CheckingBalance"));

    //Check that transfer request goes through.
    assertEquals("account_info", returnedPage);
  }

     /**
   * This test verifies that a simple transfer of $100 from Customer1's savings account to Customer2's checking account
   * will take place. Customer1's savings and checking balances will be initialized to $1000, and Customer2's savings 
   * and checking balances will be $500. The transfer occurs from Customer1's savings account to Customer2's checking account. 
   * 
   * After a successful transfer, Customer1's savings balance should reflect a $900 balance, and Customer2's checking balance should be $600. 
   * 
   * Additionally, Customer1's checking balance should reflect a $1000 balance, and Customer2's savings should reflect a $500 balance.
   * 
   * @throws SQLException
   */
  @Test
  public void testSimpleTransferSavingsToCheckingBalancesRemain() throws SQLException, ScriptException { 

    //Initialize customer1 with a balance of $1000. Balance will be represented as pennies in DB.
    double CUSTOMER1_BALANCE = 1000;
    int CUSTOMER1_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_BALANCE);
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER1_ID, CUSTOMER1_PASSWORD, CUSTOMER1_FIRST_NAME, CUSTOMER1_LAST_NAME, CUSTOMER1_BALANCE_IN_PENNIES, CUSTOMER1_BALANCE_IN_PENNIES, 0);

    //Initialize customer2 with a balance of $500. Balance will be represented as pennies in DB. 
    double CUSTOMER2_BALANCE = 500;
    int CUSTOMER2_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER2_BALANCE);
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER2_ID, CUSTOMER2_PASSWORD, CUSTOMER2_FIRST_NAME, CUSTOMER2_LAST_NAME, CUSTOMER2_BALANCE_IN_PENNIES, CUSTOMER2_BALANCE_IN_PENNIES,  0);

    //Amount to transfer
    double TRANSFER_AMOUNT = 100;
    int TRANSFER_AMOUNT_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(TRANSFER_AMOUNT);

    //Initializing users for the transfer
    User CUSTOMER1 = new User();
    CUSTOMER1.setUsername(CUSTOMER1_ID);
    CUSTOMER1.setPassword(CUSTOMER1_PASSWORD);
    CUSTOMER1.setTransferRecipientID(CUSTOMER2_ID);
    CUSTOMER1.setAmountToTransfer(TRANSFER_AMOUNT);
    CUSTOMER1.setTransferSenderAccountType(ACCOUNT_TYPE_SAVINGS);
    CUSTOMER1.setTransferRecipientAccountType(ACCOUNT_TYPE_CHECKING);
    
    //Send the transfer request.
    String returnedPage = controller.submitTransfer(CUSTOMER1);
    
    //Fetch customer1 & customer2's data from DB
    List<Map<String, Object>> customer1SqlResult = jdbcTemplate.queryForList(String.format("SELECT * FROM Customers WHERE CustomerID='%s';", CUSTOMER1_ID));
    Map<String, Object> customer1Data = customer1SqlResult.get(0);

    List<Map<String, Object>> customer2SqlResult = jdbcTemplate.queryForList(String.format("SELECT * FROM Customers WHERE CustomerID='%s';", CUSTOMER2_ID));
    Map<String, Object> customer2Data = customer2SqlResult.get(0);
   
    //Verify that customer1's balance decreased by $100. 
    assertEquals((CUSTOMER1_BALANCE_IN_PENNIES - TRANSFER_AMOUNT_IN_PENNIES), (int)customer1Data.get("SavingsBalance"));

    //Verify that customer1's checking balance remained the same. 
    assertEquals(CUSTOMER1_BALANCE_IN_PENNIES, (int)customer1Data.get("CheckingBalance"));

    //Verify that customer2's balance increased by $100.
    assertEquals((CUSTOMER2_BALANCE_IN_PENNIES + TRANSFER_AMOUNT_IN_PENNIES), (int)customer2Data.get("CheckingBalance"));

    //Verify that customer2's savings balance remained the same. 
    assertEquals(CUSTOMER2_BALANCE_IN_PENNIES, (int)customer2Data.get("SavingsBalance"));

    //Check that transfer request goes through.
    assertEquals("account_info", returnedPage);
  }

 /**
   * This test verifies that a simple transfer of $100 from Customer1 to Customer2 will take place. Customer1's balance will be
   * initialized to $1000, and Customer2's balance will be $500. The transfer occurs from Customer1's checking account to 
   * Customer2's checking account. 
   * 
   * After a successful transfer, Customer1's balance should reflect a $900 balance, and Customer2's balance should be $600. 
   * 
   * @throws SQLException
   */
  @Test
  public void testSimpleTransferCheckingAccounts() throws SQLException, ScriptException { 

    //Initialize customer1 with a balance of $1000. Balance will be represented as pennies in DB.
    double CUSTOMER1_BALANCE = 1000;
    int CUSTOMER1_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_BALANCE);
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER1_ID, CUSTOMER1_PASSWORD, CUSTOMER1_FIRST_NAME, CUSTOMER1_LAST_NAME, CUSTOMER1_BALANCE_IN_PENNIES, 0, 0);

    //Initialize customer2 with a balance of $500. Balance will be represented as pennies in DB. 
    double CUSTOMER2_BALANCE = 500;
    int CUSTOMER2_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER2_BALANCE);
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER2_ID, CUSTOMER2_PASSWORD, CUSTOMER2_FIRST_NAME, CUSTOMER2_LAST_NAME, CUSTOMER2_BALANCE_IN_PENNIES, 0, 0);

    //Amount to transfer
    double TRANSFER_AMOUNT = 100;
    int TRANSFER_AMOUNT_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(TRANSFER_AMOUNT);

    //Initializing users for the transfer
    User CUSTOMER1 = new User();
    CUSTOMER1.setUsername(CUSTOMER1_ID);
    CUSTOMER1.setPassword(CUSTOMER1_PASSWORD);
    CUSTOMER1.setTransferRecipientID(CUSTOMER2_ID);
    CUSTOMER1.setAmountToTransfer(TRANSFER_AMOUNT);
    CUSTOMER1.setTransferSenderAccountType(ACCOUNT_TYPE_CHECKING);
    CUSTOMER1.setTransferRecipientAccountType(ACCOUNT_TYPE_CHECKING);
    
    //Send the transfer request.
    String returnedPage = controller.submitTransfer(CUSTOMER1);
    
    //Fetch customer1 & customer2's data from DB
    List<Map<String, Object>> customer1SqlResult = jdbcTemplate.queryForList(String.format("SELECT * FROM Customers WHERE CustomerID='%s';", CUSTOMER1_ID));
    Map<String, Object> customer1Data = customer1SqlResult.get(0);

    List<Map<String, Object>> customer2SqlResult = jdbcTemplate.queryForList(String.format("SELECT * FROM Customers WHERE CustomerID='%s';", CUSTOMER2_ID));
    Map<String, Object> customer2Data = customer2SqlResult.get(0);
   
    //Verify that customer1's balance decreased by $100. 
    assertEquals((CUSTOMER1_BALANCE_IN_PENNIES - TRANSFER_AMOUNT_IN_PENNIES), (int)customer1Data.get("CheckingBalance"));

    //Verify that customer2's balance increased by $100.
    assertEquals((CUSTOMER2_BALANCE_IN_PENNIES + TRANSFER_AMOUNT_IN_PENNIES), (int)customer2Data.get("CheckingBalance"));

    //Check that transfer request goes through.
    assertEquals("account_info", returnedPage);
  }

  /**
   * This test verifies that a simple transfer of $100 from Customer1 to Customer2 will take place. Customer1's balance will be
   * initialized to $1000, and Customer2's balance will be $500. The transfer occurs from Customer1's savings account to 
   * Customer2's savings account. 
   * 
   * After a successful transfer, Customer1's balance should reflect a $900 balance, and Customer2's balance should be $600. 
   * 
   * @throws SQLException
   */
  @Test
  public void testSimpleTransferSavingsAccounts() throws SQLException, ScriptException { 

    //Initialize customer1 with a balance of $1000. Balance will be represented as pennies in DB.
    double CUSTOMER1_BALANCE = 1000;
    int CUSTOMER1_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_BALANCE);
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER1_ID, CUSTOMER1_PASSWORD, CUSTOMER1_FIRST_NAME, CUSTOMER1_LAST_NAME, 0, CUSTOMER1_BALANCE_IN_PENNIES, 0);

    //Initialize customer2 with a balance of $500. Balance will be represented as pennies in DB. 
    double CUSTOMER2_BALANCE = 500;
    int CUSTOMER2_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER2_BALANCE);
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER2_ID, CUSTOMER2_PASSWORD, CUSTOMER2_FIRST_NAME, CUSTOMER2_LAST_NAME, 0, CUSTOMER2_BALANCE_IN_PENNIES, 0);

    //Amount to transfer
    double TRANSFER_AMOUNT = 100;
    int TRANSFER_AMOUNT_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(TRANSFER_AMOUNT);

    //Initializing users for the transfer
    User CUSTOMER1 = new User();
    CUSTOMER1.setUsername(CUSTOMER1_ID);
    CUSTOMER1.setPassword(CUSTOMER1_PASSWORD);
    CUSTOMER1.setTransferRecipientID(CUSTOMER2_ID);
    CUSTOMER1.setAmountToTransfer(TRANSFER_AMOUNT);
    CUSTOMER1.setTransferSenderAccountType(ACCOUNT_TYPE_SAVINGS);
    CUSTOMER1.setTransferRecipientAccountType(ACCOUNT_TYPE_SAVINGS);
    
    //Send the transfer request.
    String returnedPage = controller.submitTransfer( CUSTOMER1);
    
    //Fetch customer1 & customer2's data from DB
    List<Map<String, Object>> customer1SqlResult = jdbcTemplate.queryForList(String.format("SELECT * FROM Customers WHERE CustomerID='%s';", CUSTOMER1_ID));
    Map<String, Object> customer1Data = customer1SqlResult.get(0);

    List<Map<String, Object>> customer2SqlResult = jdbcTemplate.queryForList(String.format("SELECT * FROM Customers WHERE CustomerID='%s';", CUSTOMER2_ID));
    Map<String, Object> customer2Data = customer2SqlResult.get(0);
   
    //Verify that customer1's savings balance decreased by $100. 
    assertEquals((CUSTOMER1_BALANCE_IN_PENNIES - TRANSFER_AMOUNT_IN_PENNIES), (int)customer1Data.get("SavingsBalance"));

    //Verify that customer2's savings balance increased by $100.
    assertEquals((CUSTOMER2_BALANCE_IN_PENNIES + TRANSFER_AMOUNT_IN_PENNIES), (int)customer2Data.get("SavingsBalance"));

    //Check that transfer request goes through.
    assertEquals("account_info", returnedPage);
  }

    /**
   * This test is written to check the scenario where a transfer between the sender and the recipient occurs when
   * the recipient is in overdraft in the savings account. The sender will have $1000 in the bank account and will send $100. The recipient
   * will have an overdraft balance of $101. Receiving $100 should make the sender have an updated savings overdraft balance of
   * $1. 
   * 
   * The sender's balance should decrease by the transfer amount.
   */
  @Test
  public void testTransferPaysOffOverdraftBalanceSavings() throws SQLException, ScriptException { 
    
    //Initialize customer1 with a balance of $1000. Balance will be represented as pennies in DB.
    double CUSTOMER1_BALANCE = 1000;
    int CUSTOMER1_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_BALANCE);
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER1_ID, CUSTOMER1_PASSWORD, CUSTOMER1_FIRST_NAME, CUSTOMER1_LAST_NAME, 0, CUSTOMER1_BALANCE_IN_PENNIES, 0);

    //Initialize customer2 with a balance of $0 and Overdraft balance of $101. Balance will be represented as pennies in DB. 
    double CUSTOMER2_BALANCE = 0;
    int CUSTOMER2_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER2_BALANCE);
    double CUSTOMER2_OVERDRAFT_BALANCE = 101.0;
    int CUSTOMER2_OVERDRAFT_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER2_OVERDRAFT_BALANCE);
    int CUSTOMER2_NUM_FRAUD_REVERSALS = 0;
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER2_ID, CUSTOMER2_PASSWORD, CUSTOMER2_FIRST_NAME, CUSTOMER2_LAST_NAME, 0, CUSTOMER2_BALANCE_IN_PENNIES, 0, CUSTOMER2_OVERDRAFT_BALANCE_IN_PENNIES, CUSTOMER2_NUM_FRAUD_REVERSALS, 0);

    //Amount to transfer
    double TRANSFER_AMOUNT = 100;
    int TRANSFER_AMOUNT_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(TRANSFER_AMOUNT);

    //Initializing users for the transfer
    User CUSTOMER1 = new User();
    CUSTOMER1.setUsername(CUSTOMER1_ID);
    CUSTOMER1.setPassword(CUSTOMER1_PASSWORD);

    CUSTOMER1.setTransferRecipientID(CUSTOMER2_ID);
    CUSTOMER1.setAmountToTransfer(TRANSFER_AMOUNT);
    CUSTOMER1.setTransferSenderAccountType(ACCOUNT_TYPE_SAVINGS);
    CUSTOMER1.setTransferRecipientAccountType(ACCOUNT_TYPE_SAVINGS);

    //Send the transfer request.
    String returnedPage = controller.submitTransfer(CUSTOMER1);

    //fetch customer1 & customer2's data from DB
    List<Map<String,Object>> customer1SqlResult = jdbcTemplate.queryForList(String.format("SELECT * FROM Customers WHERE CustomerID='%s';", CUSTOMER1_ID));
    Map<String, Object> customer1Data = customer1SqlResult.get(0);

    List<Map<String,Object>> customer2SqlResult = jdbcTemplate.queryForList(String.format("SELECT * FROM Customers WHERE CustomerID='%s';", CUSTOMER2_ID));
    Map<String, Object> customer2Data = customer2SqlResult.get(0);

    //Verify that customer1's balance decreased by $100. 
    assertEquals((CUSTOMER1_BALANCE_IN_PENNIES - TRANSFER_AMOUNT_IN_PENNIES), (int)customer1Data.get("SavingsBalance"));

    //Verify that customer2's overdraft balance decreased by $100.
    assertEquals((CUSTOMER2_OVERDRAFT_BALANCE_IN_PENNIES - TRANSFER_AMOUNT_IN_PENNIES), (int)customer2Data.get("SavingsOverdraftBalance"));

    //Check that transfer request goes through.
    assertEquals("account_info", returnedPage);
}

  /**
   * This test is written to check the scenario where a transfer between the sender and the recipient occurs when
   * the recipient is in overdraft in their checking account. The sender will have $1000 in the bank account and will send $100. The recipient
   * will have an overdraft balance of $101. Receiving $100 should make the sender have an updated checking overdraft balance of
   * $1. 
   * 
   * The sender's balance should decrease by the transfer amount.
   */
  @Test
  public void testTransferPaysOffOverdraftBalanceChecking() throws SQLException, ScriptException { 
    
    //Initialize customer1 with a balance of $1000. Balance will be represented as pennies in DB.
    double CUSTOMER1_BALANCE = 1000;
    int CUSTOMER1_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_BALANCE);
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER1_ID, CUSTOMER1_PASSWORD, CUSTOMER1_FIRST_NAME, CUSTOMER1_LAST_NAME, CUSTOMER1_BALANCE_IN_PENNIES, 0, 0);

    //Initialize customer2 with a balance of $0 and Overdraft balance of $101. Balance will be represented as pennies in DB. 
    double CUSTOMER2_BALANCE = 0;
    int CUSTOMER2_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER2_BALANCE);
    double CUSTOMER2_OVERDRAFT_BALANCE = 101.0;
    int CUSTOMER2_OVERDRAFT_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER2_OVERDRAFT_BALANCE);
    int CUSTOMER2_NUM_FRAUD_REVERSALS = 0;
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER2_ID, CUSTOMER2_PASSWORD, CUSTOMER2_FIRST_NAME, CUSTOMER2_LAST_NAME, CUSTOMER2_BALANCE_IN_PENNIES, 0, CUSTOMER2_OVERDRAFT_BALANCE_IN_PENNIES, 0, CUSTOMER2_NUM_FRAUD_REVERSALS, 0);

    //Amount to transfer
    double TRANSFER_AMOUNT = 100;
    int TRANSFER_AMOUNT_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(TRANSFER_AMOUNT);

    //Initializing users for the transfer
    User CUSTOMER1 = new User();
    CUSTOMER1.setUsername(CUSTOMER1_ID);
    CUSTOMER1.setPassword(CUSTOMER1_PASSWORD);

    CUSTOMER1.setTransferRecipientID(CUSTOMER2_ID);
    CUSTOMER1.setAmountToTransfer(TRANSFER_AMOUNT);
    CUSTOMER1.setTransferSenderAccountType(ACCOUNT_TYPE_CHECKING);
    CUSTOMER1.setTransferRecipientAccountType(ACCOUNT_TYPE_CHECKING);

    //Send the transfer request.
    String returnedPage = controller.submitTransfer(CUSTOMER1);

    //fetch customer1 & customer2's data from DB
    List<Map<String,Object>> customer1SqlResult = jdbcTemplate.queryForList(String.format("SELECT * FROM Customers WHERE CustomerID='%s';", CUSTOMER1_ID));
    Map<String, Object> customer1Data = customer1SqlResult.get(0);

    List<Map<String,Object>> customer2SqlResult = jdbcTemplate.queryForList(String.format("SELECT * FROM Customers WHERE CustomerID='%s';", CUSTOMER2_ID));
    Map<String, Object> customer2Data = customer2SqlResult.get(0);

    //Verify that customer1's balance decreased by $100. 
    assertEquals((CUSTOMER1_BALANCE_IN_PENNIES - TRANSFER_AMOUNT_IN_PENNIES), (int)customer1Data.get("CheckingBalance"));

    //Verify that customer2's overdraft balance decreased by $100.
    assertEquals((CUSTOMER2_OVERDRAFT_BALANCE_IN_PENNIES - TRANSFER_AMOUNT_IN_PENNIES), (int)customer2Data.get("CheckingOverdraftBalance"));

    //Check that transfer request goes through.
    assertEquals("account_info", returnedPage);
}

/**
 * This test will test a scenario where the sender sends the recipient (who currently has an overdraft balance in the checking account) an amount
 * that clears the recipient's checking overdraft balance and deposits the remainder. 
 * 
 * The sender will be initialized with $1000, and the recipient will have a checking overdraft balance of $100. Due to applied interest,
 * the recipient will have a checking overdraft balance of $102. The sender will send $150, so the recipient's balance should reflect $48
 * after the transfer.
 * 
 * @throws SQLException
 * @throws ScriptException
 */
@Test
public void testTransferPaysOverdraftAndDepositsRemainderChecking() throws SQLException, ScriptException { 

    //Initialize customer1 with a balance of $1000. Balance will be represented as pennies in DB.
    double CUSTOMER1_BALANCE = 1000;
    int CUSTOMER1_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_BALANCE);
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER1_ID, CUSTOMER1_PASSWORD, CUSTOMER1_FIRST_NAME, CUSTOMER1_LAST_NAME, CUSTOMER1_BALANCE_IN_PENNIES, 0, 0);

    double CUSTOMER2_BALANCE = 0;
    int CUSTOMER2_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER2_BALANCE);
    double CUSTOMER2_OVERDRAFT_BALANCE = 100.0;
    int CUSTOMER2_OVERDRAFT_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER2_OVERDRAFT_BALANCE);
    int CUSTOMER2_NUM_FRAUD_REVERSALS = 0;
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER2_ID, CUSTOMER2_PASSWORD, CUSTOMER2_FIRST_NAME, CUSTOMER2_LAST_NAME, CUSTOMER2_BALANCE_IN_PENNIES, 0, CUSTOMER2_OVERDRAFT_BALANCE_IN_PENNIES, 0, CUSTOMER2_NUM_FRAUD_REVERSALS, 0);

    //Transfer $150 from sender's account to recipient's account.
    double TRANSFER_AMOUNT = 150;
    int TRANSFER_AMOUNT_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(TRANSFER_AMOUNT);

    //Initializing users for the transfer
    User CUSTOMER1 = new User();
    CUSTOMER1.setUsername(CUSTOMER1_ID);
    CUSTOMER1.setPassword(CUSTOMER1_PASSWORD);

    CUSTOMER1.setTransferRecipientID(CUSTOMER2_ID);
    CUSTOMER1.setAmountToTransfer(TRANSFER_AMOUNT);
    CUSTOMER1.setTransferSenderAccountType(ACCOUNT_TYPE_CHECKING);
    CUSTOMER1.setTransferRecipientAccountType(ACCOUNT_TYPE_CHECKING);

    //Send the transfer request.
    String returnedPage = controller.submitTransfer(CUSTOMER1);

    //fetch customer1 & customer2's data from DB
    List<Map<String,Object>> customer1SqlResult = jdbcTemplate.queryForList(String.format("SELECT * FROM Customers WHERE CustomerID='%s';", CUSTOMER1_ID));
    Map<String, Object> customer1Data = customer1SqlResult.get(0);

    List<Map<String,Object>> customer2SqlResult = jdbcTemplate.queryForList(String.format("SELECT * FROM Customers WHERE CustomerID='%s';", CUSTOMER2_ID));
    Map<String, Object> customer2Data = customer2SqlResult.get(0);

    //Verify that customer1's balance decreased by $100. 
    assertEquals((CUSTOMER1_BALANCE_IN_PENNIES - TRANSFER_AMOUNT_IN_PENNIES), (int)customer1Data.get("CheckingBalance"));

    //Verify that customer2's overdraft balance is now $0.
    assertEquals(0, (int)customer2Data.get("CheckingOverdraftBalance"));

    //Verify that customer2's balance reflects a positive amount due to a remainder being leftover after the transfer amount - overdraft balance.
    int CUSTOMER2_EXPECTED_BALANCE_IN_PENNIES = TRANSFER_AMOUNT_IN_PENNIES - CUSTOMER2_OVERDRAFT_BALANCE_IN_PENNIES;
    assertEquals(CUSTOMER2_EXPECTED_BALANCE_IN_PENNIES, (int)customer2Data.get("CheckingBalance"));

    //Check that transfer request goes through.
    assertEquals("account_info", returnedPage);
  }

  /**
 * This test will test a scenario where the sender sends the recipient (who currently has a savings overdraft balance) an amount
 * that clears the recipient's savings overdraft balance and deposits the remainder. 
 * 
 * The sender will be initialized with $1000, and the recipient will have a savings overdraft balance of $100. Due to applied interest,
 * the recipient will have a savings overdraft balance of $102. The sender will send $150, so the recipient's balance should reflect $48
 * after the transfer.
 * 
 * @throws SQLException
 * @throws ScriptException
 */
@Test
public void testTransferPaysOverdraftAndDepositsRemainderSavings() throws SQLException, ScriptException { 

    //Initialize customer1 with a balance of $1000. Balance will be represented as pennies in DB.
    double CUSTOMER1_BALANCE = 1000;
    int CUSTOMER1_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_BALANCE);
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER1_ID, CUSTOMER1_PASSWORD, CUSTOMER1_FIRST_NAME, CUSTOMER1_LAST_NAME, 0, CUSTOMER1_BALANCE_IN_PENNIES, 0);

    double CUSTOMER2_BALANCE = 0;
    int CUSTOMER2_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER2_BALANCE);
    double CUSTOMER2_OVERDRAFT_BALANCE = 100.0;
    int CUSTOMER2_OVERDRAFT_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER2_OVERDRAFT_BALANCE);
    int CUSTOMER2_NUM_FRAUD_REVERSALS = 0;
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER2_ID, CUSTOMER2_PASSWORD, CUSTOMER2_FIRST_NAME, CUSTOMER2_LAST_NAME, 0, CUSTOMER2_BALANCE_IN_PENNIES, 0, CUSTOMER2_OVERDRAFT_BALANCE_IN_PENNIES, CUSTOMER2_NUM_FRAUD_REVERSALS, 0);

    //Transfer $150 from sender's account to recipient's account.
    double TRANSFER_AMOUNT = 150;
    int TRANSFER_AMOUNT_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(TRANSFER_AMOUNT);

    //Initializing users for the transfer
    User CUSTOMER1 = new User();
    CUSTOMER1.setUsername(CUSTOMER1_ID);
    CUSTOMER1.setPassword(CUSTOMER1_PASSWORD);

    CUSTOMER1.setTransferRecipientID(CUSTOMER2_ID);
    CUSTOMER1.setAmountToTransfer(TRANSFER_AMOUNT);
    CUSTOMER1.setTransferSenderAccountType(ACCOUNT_TYPE_SAVINGS);
    CUSTOMER1.setTransferRecipientAccountType(ACCOUNT_TYPE_SAVINGS);

    //Send the transfer request.
    String returnedPage = controller.submitTransfer(CUSTOMER1);

    //fetch customer1 & customer2's data from DB
    List<Map<String,Object>> customer1SqlResult = jdbcTemplate.queryForList(String.format("SELECT * FROM Customers WHERE CustomerID='%s';", CUSTOMER1_ID));
    Map<String, Object> customer1Data = customer1SqlResult.get(0);

    List<Map<String,Object>> customer2SqlResult = jdbcTemplate.queryForList(String.format("SELECT * FROM Customers WHERE CustomerID='%s';", CUSTOMER2_ID));
    Map<String, Object> customer2Data = customer2SqlResult.get(0);

    //Verify that customer1's balance decreased by $100. 
    assertEquals((CUSTOMER1_BALANCE_IN_PENNIES - TRANSFER_AMOUNT_IN_PENNIES), (int)customer1Data.get("SavingsBalance"));

    //Verify that customer2's overdraft balance is now $0.
    assertEquals(0, (int)customer2Data.get("SavingsOverdraftBalance"));

    //Verify that customer2's balance reflects a positive amount due to a remainder being leftover after the transfer amount - overdraft balance.
    int CUSTOMER2_EXPECTED_BALANCE_IN_PENNIES = TRANSFER_AMOUNT_IN_PENNIES - CUSTOMER2_OVERDRAFT_BALANCE_IN_PENNIES;
    assertEquals(CUSTOMER2_EXPECTED_BALANCE_IN_PENNIES, (int)customer2Data.get("SavingsBalance"));

    //Check that transfer request goes through.
    assertEquals("account_info", returnedPage);
  }

   /**
   * This test verifies that a simple transfer of $100 from Customer1's checking to savings account will take place. 
   * The initial checking balance will be $1000 and the initial savings balance will be $500. The checking transfer occurs 
   * from Customer1's checking account to savings account. 
   * 
   * After a successful transfer, Customer1's checking balance should reflect a $500 balance, and their savings balance should be $1000. 
   * 
   * @throws SQLException
   */
  @Test
  public void testSimpleCheckingTransfer() throws SQLException, ScriptException { 

    //Initialize customer1 with a balance of $1000. Balance will be represented as pennies in DB.
    double CUSTOMER1_CHECKING_BALANCE = 1000;
    double CUSTOMER1_SAVINGS_BALANCE = 500;
    int CUSTOMER1_CHECKING_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_CHECKING_BALANCE);
    int CUSTOMER1_SAVINGS_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_SAVINGS_BALANCE);
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER1_ID, CUSTOMER1_PASSWORD, CUSTOMER1_FIRST_NAME, CUSTOMER1_LAST_NAME, CUSTOMER1_CHECKING_BALANCE_IN_PENNIES, CUSTOMER1_SAVINGS_BALANCE_IN_PENNIES, 0);
    
    //Amount to transfer
    double TRANSFER_AMOUNT = 500;
    int TRANSFER_AMOUNT_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(TRANSFER_AMOUNT);

    //Initializing users for the transfer
    User CUSTOMER1 = new User();
    CUSTOMER1.setUsername(CUSTOMER1_ID);
    CUSTOMER1.setPassword(CUSTOMER1_PASSWORD);
    CUSTOMER1.setAmountToTransfer(TRANSFER_AMOUNT);
    
    //Send the transfer request.
    String returnedPage = controller.submitCheckingTransfer(CUSTOMER1);
    
    //Fetch customer1's' data from DB
    List<Map<String, Object>> customer1SqlResult = jdbcTemplate.queryForList(String.format("SELECT * FROM Customers WHERE CustomerID='%s';", CUSTOMER1_ID));
    Map<String, Object> customer1Data = customer1SqlResult.get(0);
   
    //Verify that customer1's checking balance decreased by $500. 
    assertEquals((CUSTOMER1_CHECKING_BALANCE_IN_PENNIES - TRANSFER_AMOUNT_IN_PENNIES), (int)customer1Data.get("CheckingBalance"));

    //Verify that customer1's saving balance increased by $500.
    assertEquals((CUSTOMER1_SAVINGS_BALANCE_IN_PENNIES + TRANSFER_AMOUNT_IN_PENNIES), (int)customer1Data.get("SavingsBalance"));

    //Check that transfer request goes through.
    assertEquals("account_info", returnedPage);
  }

    /**
 * This test will test a scenario where the user transfers and amount from their checking account to their savings account, 
 * which currently has an overdraft balance, that clears the savings overdraft balance and deposits the remainder. 
 * 
 * The user will be initialized with $1000 in their checking account and will have a savings overdraft balance of $100. Due to applied interest,
 * they will have a savings overdraft balance of $102. The savings account will recieve $150, so the savings balance should reflect $48
 * after the transfer.
 * 
 * @throws SQLException
 * @throws ScriptException
 */
@Test
public void testCheckingTransferPaysOverdraftAndDepositsRemainderSavings() throws SQLException, ScriptException { 

    //Initialize customer1 with a checking balance of $1000 and a savings overdraft balance of 100. Balance will be represented as pennies in DB.
    double CUSTOMER1_CHECKING_BALANCE = 1000;
    int CUSTOMER1_CHECKING_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_CHECKING_BALANCE);
    double CUSTOMER1_SAVINGS_OVERDRAFT_BALANCE = 100.0;
    int CUSTOMER1_SAVINGS_OVERDRAFT_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_SAVINGS_OVERDRAFT_BALANCE);
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER1_ID, CUSTOMER1_PASSWORD, CUSTOMER1_FIRST_NAME, CUSTOMER1_LAST_NAME, CUSTOMER1_CHECKING_BALANCE_IN_PENNIES, 0, 0, CUSTOMER1_SAVINGS_OVERDRAFT_BALANCE_IN_PENNIES, 0, 0);

    //Transfer $150 from checking to savings account.
    double TRANSFER_AMOUNT = 150;
    int TRANSFER_AMOUNT_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(TRANSFER_AMOUNT);

    //Initializing users for the transfer
    User CUSTOMER1 = new User();
    CUSTOMER1.setUsername(CUSTOMER1_ID);
    CUSTOMER1.setPassword(CUSTOMER1_PASSWORD);
    CUSTOMER1.setAmountToTransfer(TRANSFER_AMOUNT);

    //Send the transfer request.
    String returnedPage = controller.submitCheckingTransfer(CUSTOMER1);

    //fetch customer1's data from DB
    List<Map<String,Object>> customer1SqlResult = jdbcTemplate.queryForList(String.format("SELECT * FROM Customers WHERE CustomerID='%s';", CUSTOMER1_ID));
    Map<String, Object> customer1Data = customer1SqlResult.get(0);

    //Verify that customer1's checking balance decreased by $100. 
    assertEquals((CUSTOMER1_CHECKING_BALANCE_IN_PENNIES - TRANSFER_AMOUNT_IN_PENNIES), (int)customer1Data.get("CheckingBalance"));

    //Verify that customer1's savings overdraft balance is now $0.
    assertEquals(0, (int)customer1Data.get("SavingsOverdraftBalance"));

    //Verify that the savings balance reflects a positive amount due to a remainder being leftover after the transfer amount - overdraft balance.
    int CUSTOMER1_EXPECTED_BALANCE_IN_PENNIES = TRANSFER_AMOUNT_IN_PENNIES - CUSTOMER1_SAVINGS_OVERDRAFT_BALANCE_IN_PENNIES;
    assertEquals(CUSTOMER1_EXPECTED_BALANCE_IN_PENNIES, (int)customer1Data.get("SavingsBalance"));

    //Check that transfer request goes through.
    assertEquals("account_info", returnedPage);
  }

  /**
   * This test is written to check the scenario where a checking transfer occurs when the savings account is in overdraft. 
   * The user's checking balance is $1000 and they transfer $100 to the savings account. The savings account
   * will have an overdraft balance of $101. Receiving $100 should make the savings account have an updated savings 
   * overdraft balance of $1. 
   * 
   * The checking balance should decrease by the transfer amount.
   */
  @Test
  public void testCheckingTransferPaysOffOverdraftBalance() throws SQLException, ScriptException { 
    
    //Initialize customer1 with a checking balance of $1000 and savings overdraft of $101. Balance will be represented as pennies in DB.
    double CUSTOMER1_CHECKING_BALANCE = 1000;
    int CUSTOMER1_CHECKING_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_CHECKING_BALANCE);
    double CUSTOMER1_SAVINGS_OVERDRAFT_BALANCE = 101.0;
    int CUSTOMER1_SAVINGS_OVERDRAFT_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_SAVINGS_OVERDRAFT_BALANCE);
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER1_ID, CUSTOMER1_PASSWORD, CUSTOMER1_FIRST_NAME, CUSTOMER1_LAST_NAME, CUSTOMER1_CHECKING_BALANCE_IN_PENNIES, 0, 0, CUSTOMER1_SAVINGS_OVERDRAFT_BALANCE_IN_PENNIES, 0, 0);

    //Amount to transfer
    double TRANSFER_AMOUNT = 100;
    int TRANSFER_AMOUNT_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(TRANSFER_AMOUNT);

    //Initializing users for the transfer
    User CUSTOMER1 = new User();
    CUSTOMER1.setUsername(CUSTOMER1_ID);
    CUSTOMER1.setPassword(CUSTOMER1_PASSWORD);
    CUSTOMER1.setAmountToTransfer(TRANSFER_AMOUNT);

    //Send the transfer request.
    String returnedPage = controller.submitCheckingTransfer(CUSTOMER1);

    //fetch customer1's data from DB
    List<Map<String,Object>> customer1SqlResult = jdbcTemplate.queryForList(String.format("SELECT * FROM Customers WHERE CustomerID='%s';", CUSTOMER1_ID));
    Map<String, Object> customer1Data = customer1SqlResult.get(0);

    //Verify that customer1's checking balance decreased by $100. 
    assertEquals((CUSTOMER1_CHECKING_BALANCE_IN_PENNIES - TRANSFER_AMOUNT_IN_PENNIES), (int)customer1Data.get("CheckingBalance"));

    //Verify that customer1's overdraft balance decreased by $100.
    assertEquals((CUSTOMER1_SAVINGS_OVERDRAFT_BALANCE_IN_PENNIES - TRANSFER_AMOUNT_IN_PENNIES), (int)customer1Data.get("SavingsOverdraftBalance"));

    //Check that transfer request goes through.
    assertEquals("account_info", returnedPage);
  }

  /**
   * This test verifies that a simple transfer of $100 from Customer1's savings to checking account will take place. 
   * The initial checking balance will be $500 and the initial savings balance will be $1000. The checking transfer occurs 
   * from Customer1's savings to checking account  account. 
   * 
   * After a successful transfer, Customer1's checking balance should reflect a $1000 balance, and their savings balance should be $500. 
   * 
   * @throws SQLException
   */
  @Test
  public void testSimpleSavingsTransfer() throws SQLException, ScriptException { 

    //Initialize customer1 with a checking balance of $500 and savings balance of $1000. Balance will be represented as pennies in DB.
    double CUSTOMER1_CHECKING_BALANCE = 500;
    double CUSTOMER1_SAVINGS_BALANCE = 1000;
    int CUSTOMER1_CHECKING_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_CHECKING_BALANCE);
    int CUSTOMER1_SAVINGS_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_SAVINGS_BALANCE);
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER1_ID, CUSTOMER1_PASSWORD, CUSTOMER1_FIRST_NAME, CUSTOMER1_LAST_NAME, CUSTOMER1_CHECKING_BALANCE_IN_PENNIES, CUSTOMER1_SAVINGS_BALANCE_IN_PENNIES, 0);
    
    //Amount to transfer
    double TRANSFER_AMOUNT = 500;
    int TRANSFER_AMOUNT_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(TRANSFER_AMOUNT);

    //Initializing users for the transfer
    User CUSTOMER1 = new User();
    CUSTOMER1.setUsername(CUSTOMER1_ID);
    CUSTOMER1.setPassword(CUSTOMER1_PASSWORD);
    CUSTOMER1.setAmountToTransfer(TRANSFER_AMOUNT);
    
    //Send the transfer request.
    String returnedPage = controller.submitSavingsTransfer(CUSTOMER1);
    
    //Fetch customer1's' data from DB
    List<Map<String, Object>> customer1SqlResult = jdbcTemplate.queryForList(String.format("SELECT * FROM Customers WHERE CustomerID='%s';", CUSTOMER1_ID));
    Map<String, Object> customer1Data = customer1SqlResult.get(0);
   
    //Verify that customer1's checking balance increased by $500. 
    assertEquals((CUSTOMER1_CHECKING_BALANCE_IN_PENNIES + TRANSFER_AMOUNT_IN_PENNIES), (int)customer1Data.get("CheckingBalance"));

    //Verify that customer1's saving balance decreased by $500.
    assertEquals((CUSTOMER1_SAVINGS_BALANCE_IN_PENNIES - TRANSFER_AMOUNT_IN_PENNIES), (int)customer1Data.get("SavingsBalance"));

    //Check that transfer request goes through.
    assertEquals("account_info", returnedPage);
  }

  /**
 * This test will test a scenario where the user transfers and amount from their savings account to their checking account, 
 * which currently has an overdraft balance, that clears the checking overdraft balance and deposits the remainder. 
 * 
 * The user will be initialized with $1000 in their savings account and will have a checking overdraft balance of $100. Due to applied interest,
 * they will have a checking overdraft balance of $102. The checking account will recieve $150, so the checking balance should reflect $48
 * after the transfer.
 * 
 * @throws SQLException
 * @throws ScriptException
 */
@Test
public void testSavingsTransferPaysOverdraftAndDepositsRemainderSavings() throws SQLException, ScriptException { 

    //Initialize customer1 with a checking balance of $1000 and a savings overdraft balance of 100. Balance will be represented as pennies in DB.
    double CUSTOMER1_SAVINGS_BALANCE = 1000;
    int CUSTOMER1_SAVINGS_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_SAVINGS_BALANCE);
    double CUSTOMER1_CHECKING_OVERDRAFT_BALANCE = 100.0;
    int CUSTOMER1_CHECKING_OVERDRAFT_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_CHECKING_OVERDRAFT_BALANCE);
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER1_ID, CUSTOMER1_PASSWORD, CUSTOMER1_FIRST_NAME, CUSTOMER1_LAST_NAME, 0, CUSTOMER1_SAVINGS_BALANCE_IN_PENNIES, CUSTOMER1_CHECKING_OVERDRAFT_BALANCE_IN_PENNIES, 0, 0, 0);

    //Transfer $150 from checking to savings account.
    double TRANSFER_AMOUNT = 150;
    int TRANSFER_AMOUNT_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(TRANSFER_AMOUNT);

    //Initializing users for the transfer
    User CUSTOMER1 = new User();
    CUSTOMER1.setUsername(CUSTOMER1_ID);
    CUSTOMER1.setPassword(CUSTOMER1_PASSWORD);
    CUSTOMER1.setAmountToTransfer(TRANSFER_AMOUNT);

    //Send the transfer request.
    String returnedPage = controller.submitSavingsTransfer(CUSTOMER1);

    //fetch customer1's data from DB
    List<Map<String,Object>> customer1SqlResult = jdbcTemplate.queryForList(String.format("SELECT * FROM Customers WHERE CustomerID='%s';", CUSTOMER1_ID));
    Map<String, Object> customer1Data = customer1SqlResult.get(0);

    //Verify that customer1's savings balance decreased by $100. 
    assertEquals((CUSTOMER1_SAVINGS_BALANCE_IN_PENNIES - TRANSFER_AMOUNT_IN_PENNIES), (int)customer1Data.get("SavingsBalance"));

    //Verify that customer1's savings overdraft balance is now $0.
    assertEquals(0, (int)customer1Data.get("CheckingOverdraftBalance"));

    //Verify that the savings balance reflects a positive amount due to a remainder being leftover after the transfer amount - overdraft balance.
    int CUSTOMER1_EXPECTED_BALANCE_IN_PENNIES = TRANSFER_AMOUNT_IN_PENNIES - CUSTOMER1_CHECKING_OVERDRAFT_BALANCE_IN_PENNIES;
    assertEquals(CUSTOMER1_EXPECTED_BALANCE_IN_PENNIES, (int)customer1Data.get("CheckingBalance"));

    //Check that transfer request goes through.
    assertEquals("account_info", returnedPage);
  }

  /**
   * This test is written to check the scenario where a savings transfer occurs when the checking account is in overdraft. 
   * The user's savings balance is $1000 and they transfer $100 to the checking account. The checking account checking 
   * overdraft balance of $1. 
   * 
   * The savings balance should decrease by the transfer amount.
   */
  @Test
  public void testSavingsTransferPaysOffOverdraftBalance() throws SQLException, ScriptException { 
    
    //Initialize customer1 with a savings balance of $1000 and checking overdraft of $101. Balance will be represented as pennies in DB.
    double CUSTOMER1_SAVINGS_BALANCE = 1000;
    int CUSTOMER1_SAVINGS_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_SAVINGS_BALANCE);
    double CUSTOMER1_CHECKING_OVERDRAFT_BALANCE = 101.0;
    int CUSTOMER1_CHECKING_OVERDRAFT_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_CHECKING_OVERDRAFT_BALANCE);
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER1_ID, CUSTOMER1_PASSWORD, CUSTOMER1_FIRST_NAME, CUSTOMER1_LAST_NAME, 0, CUSTOMER1_SAVINGS_BALANCE_IN_PENNIES, CUSTOMER1_CHECKING_OVERDRAFT_BALANCE_IN_PENNIES, 0, 0, 0);

    //Amount to transfer
    double TRANSFER_AMOUNT = 100;
    int TRANSFER_AMOUNT_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(TRANSFER_AMOUNT);

    //Initializing users for the transfer
    User CUSTOMER1 = new User();
    CUSTOMER1.setUsername(CUSTOMER1_ID);
    CUSTOMER1.setPassword(CUSTOMER1_PASSWORD);
    CUSTOMER1.setAmountToTransfer(TRANSFER_AMOUNT);

    //Send the transfer request.
    String returnedPage = controller.submitSavingsTransfer(CUSTOMER1);

    //fetch customer1's data from DB
    List<Map<String,Object>> customer1SqlResult = jdbcTemplate.queryForList(String.format("SELECT * FROM Customers WHERE CustomerID='%s';", CUSTOMER1_ID));
    Map<String, Object> customer1Data = customer1SqlResult.get(0);

    //Verify that customer1's savings balance decreased by $100. 
    assertEquals((CUSTOMER1_SAVINGS_BALANCE_IN_PENNIES - TRANSFER_AMOUNT_IN_PENNIES), (int)customer1Data.get("SavingsBalance"));

    //Verify that customer1's overdraft balance decreased by $100.
    assertEquals((CUSTOMER1_CHECKING_OVERDRAFT_BALANCE_IN_PENNIES - TRANSFER_AMOUNT_IN_PENNIES), (int)customer1Data.get("CheckingOverdraftBalance"));

    //Check that transfer request goes through.
    assertEquals("account_info", returnedPage);
}

  /**
   * Enum for {@link CryptoTransactionTester}
   */
  @AllArgsConstructor
  enum CryptoTransactionTestType {
    BUY("Buy", "CryptoBuy"),
    SELL("Sell", "CryptoSell");
    final String cryptoHistoryActionName;
    final String transactionHistoryActionName;
  }

  /**
   * Class to represent transaction properties for {@link CryptoTransactionTester}
   */
  @Builder
  static class CryptoTransaction {
    /**
     * The name of the cryptocurrency for the transaction
     */
    final String cryptoName;

    /**
     * The price of the cryptocurrency in dollars at the time of the transaction
     */
    final double cryptoPrice;

    /**
     * The expected (cash) balance of the user after the transaction
     */
    final double expectedEndingBalanceInDollars;

    /**
     * The (cash) overdraft balance of the user before the transaction takes place
     */
    @Builder.Default
    final double initialOverdraftBalanceInDollars = 0.0;

    /**
     * The expected ending overdraft balance of the user
     */
    @Builder.Default
    final double expectedEndingOverdraftBalanceInDollars = 0.0;

    /**
     * The expected ending crypto balance of the user
     */
    @Builder.Default
    double expectedEndingCryptoBalance = 0.0;

    /**
     * The amount of cryptocurrency to buy (in units of the cryptocurrency)
     */
    final double cryptoAmountToTransact;

    /**
     * Whether the transaction is made with the correct password
     */
    @Builder.Default
    final boolean validPassword = true;

    /**
     * Whether the transaction is expected to succeed with the supplied parameters
     */
    final boolean shouldSucceed;

    /**
     * Whether the transaction should add to the overdraft logs
     */
    @Builder.Default
    final boolean overdraftTransaction = false;

    /**
     * The type of the transaction (buy or sell)
     */
    final CryptoTransactionTestType cryptoTransactionTestType;
  }

  /**
   * Helper class to test crypto buying and selling in with various parameters.
   * This does several checks to see if the transaction took place correctly.
   */
  @Builder
  static class CryptoTransactionTester {

    /**
     * The initial (cash) balance of the user
     */
    final double initialBalanceInDollars;

    /**
     * The (cash) overdraft balance of the user
     */
    @Builder.Default
    final double initialOverdraftBalanceInDollars = 0.0;

    /**
     * The initial cryptocurrency balance of the user in units of cryptocurrency
     * Map of cryptocurrency name to initial balance
     */
    @Builder.Default
    final Map<String, Double> initialCryptoBalance = Collections.emptyMap();

    void initialize() throws ScriptException {
      int balanceInPennies = MvcControllerIntegTestHelpers.convertDollarsToPennies(initialBalanceInDollars);
      MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER1_ID, CUSTOMER1_PASSWORD, CUSTOMER1_FIRST_NAME,
              CUSTOMER1_LAST_NAME, balanceInPennies, 0, MvcControllerIntegTestHelpers.convertDollarsToPennies(initialOverdraftBalanceInDollars), 0, 0, 0);
      for (Map.Entry<String, Double> initialBalance : initialCryptoBalance.entrySet()) {
        MvcControllerIntegTestHelpers.setCryptoBalance(dbDelegate, CUSTOMER1_ID, initialBalance.getKey(), initialBalance.getValue());
      }
    }

    // Counter for number of transactions completed by this tester
    private int numTransactions = 0;

    // Counter for the number of overdraft transaction completed by this tester
    private int numOverdraftTransactions = 0;

    /**
     * Attempts a transaction
     */
    void test(CryptoTransaction transaction) {
      User user = new User();
      user.setUsername(CUSTOMER1_ID);
      if (transaction.validPassword) {
        user.setPassword(CUSTOMER1_PASSWORD);
      } else {
        user.setPassword("wrong_password");
      }
      user.setWhichCryptoToBuy(transaction.cryptoName);


      // Mock the price of the cryptocurrency
      Mockito.when(cryptoPriceClient.getCurrentCryptoValue(transaction.cryptoName)).thenReturn(transaction.cryptoPrice);

      // attempt transaction
      LocalDateTime cryptoTransactionTime = MvcControllerIntegTestHelpers.fetchCurrentTimeAsLocalDateTimeNoMilliseconds();
      String returnedPage;
      if (transaction.cryptoTransactionTestType == CryptoTransactionTestType.BUY) {
        user.setAmountToBuyCrypto(transaction.cryptoAmountToTransact);
        returnedPage = controller.buyCrypto(user);
      } else {
        user.setAmountToSellCrypto(transaction.cryptoAmountToTransact);
        returnedPage = controller.sellCrypto(user);
      }

      // check the crypto balance
      try {
        double endingCryptoBalance = jdbcTemplate.queryForObject("SELECT CryptoAmount FROM CryptoHoldings WHERE CustomerID=? AND CryptoName=?", BigDecimal.class, CUSTOMER1_ID, transaction.cryptoName).doubleValue();
        assertEquals(transaction.expectedEndingCryptoBalance, endingCryptoBalance);
      } catch (EmptyResultDataAccessException e) {
        assertEquals(transaction.expectedEndingCryptoBalance, 0);
      }

      // check the cash balance
      assertEquals(MvcControllerIntegTestHelpers.convertDollarsToPennies(transaction.expectedEndingBalanceInDollars),
              jdbcTemplate.queryForObject("SELECT CheckingBalance FROM Customers WHERE CustomerID=?", Integer.class, CUSTOMER1_ID));

      // check the overdraft balance
      assertEquals(MvcControllerIntegTestHelpers.convertDollarsToPennies(transaction.expectedEndingOverdraftBalanceInDollars),
              jdbcTemplate.queryForObject("SELECT CheckingOverdraftBalance FROM Customers WHERE CustomerID=?", Integer.class, CUSTOMER1_ID));

      if (!transaction.shouldSucceed) {
        // verify no transaction took place
        assertEquals("welcome", returnedPage);
        assertEquals(numTransactions, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM CheckingTransactionHistory;", Integer.class));
        assertEquals(numTransactions, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM CryptoHistory;", Integer.class));
        assertEquals(numOverdraftTransactions, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM CheckingOverdraftLogs;", Integer.class));
      } else {
        assertEquals("account_info", returnedPage);

        // check transaction logs
        assertEquals(numTransactions + 1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM CheckingTransactionHistory;", Integer.class));
        List<Map<String, Object>> transactionHistoryTableData = jdbcTemplate.queryForList("SELECT * FROM CheckingTransactionHistory ORDER BY Timestamp DESC;");
        Map<String, Object> customer1TransactionLog = transactionHistoryTableData.get(0);
        int expectedCryptoValueInPennies = MvcControllerIntegTestHelpers.convertDollarsToPennies(transaction.cryptoPrice * transaction.cryptoAmountToTransact);
        MvcControllerIntegTestHelpers.checkTransactionLog(customer1TransactionLog, cryptoTransactionTime, CUSTOMER1_ID, transaction.cryptoTransactionTestType.transactionHistoryActionName, expectedCryptoValueInPennies);

        // check crypto logs
        assertEquals(numTransactions + 1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM CryptoHistory;", Integer.class));
        List<Map<String, Object>> cryptoHistoryTableData = jdbcTemplate.queryForList("SELECT * FROM CryptoHistory ORDER BY Timestamp DESC;");
        Map<String, Object> customer1CryptoLog = cryptoHistoryTableData.get(0);
        MvcControllerIntegTestHelpers.checkCryptoLog(customer1CryptoLog, cryptoTransactionTime, CUSTOMER1_ID, transaction.cryptoTransactionTestType.cryptoHistoryActionName,
                transaction.cryptoName, transaction.cryptoAmountToTransact);

        // check overdraft logs (if applicable)
        if (transaction.overdraftTransaction) {
          assertEquals(numOverdraftTransactions + 1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM CheckingOverdraftLogs;", Integer.class));
          List<Map<String, Object>> overdraftLogTableData = jdbcTemplate.queryForList("SELECT * FROM CheckingOverdraftLogs ORDER BY Timestamp DESC;");
          Map<String, Object> customer1OverdraftLog = overdraftLogTableData.get(0);
          MvcControllerIntegTestHelpers.checkOverdraftLog(customer1OverdraftLog, cryptoTransactionTime, CUSTOMER1_ID, expectedCryptoValueInPennies,
                  MvcControllerIntegTestHelpers.convertDollarsToPennies(transaction.initialOverdraftBalanceInDollars),
                  MvcControllerIntegTestHelpers.convertDollarsToPennies(transaction.expectedEndingOverdraftBalanceInDollars));
          numOverdraftTransactions++;
        } else {
          assertEquals(numOverdraftTransactions, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM CheckingOverdraftLogs;", Integer.class));
        }

        numTransactions++;

      }
    }
  }

  /**
   * Test that no crypto buy transaction occurs when the user password is incorrect
   */
  @Test
  public void testCryptoBuyInvalidPassword() throws ScriptException {
    CryptoTransactionTester cryptoTransactionTester = CryptoTransactionTester.builder()
            .initialBalanceInDollars(1000)
            .build();

    cryptoTransactionTester.initialize();

    CryptoTransaction cryptoTransaction = CryptoTransaction.builder()
            .expectedEndingBalanceInDollars(1000)
            .cryptoPrice(1000)
            .cryptoAmountToTransact(0.1)
            .cryptoName("ETH")
            .validPassword(false)
            .cryptoTransactionTestType(CryptoTransactionTestType.BUY)
            .shouldSucceed(false)
            .build();
    cryptoTransactionTester.test(cryptoTransaction);
  }

  /**
   * Test that no crypto sell transaction occurs when the user password is incorrect
   */
  @Test
  public void testCryptoSellInvalidPassword() throws ScriptException {
    CryptoTransactionTester cryptoTransactionTester = CryptoTransactionTester.builder()
            .initialBalanceInDollars(1000)
            .build();

    cryptoTransactionTester.initialize();

    CryptoTransaction cryptoTransaction = CryptoTransaction.builder()
            .expectedEndingBalanceInDollars(1000)
            .cryptoPrice(1000)
            .cryptoAmountToTransact(0.1)
            .validPassword(false)
            .cryptoName("ETH")
            .cryptoTransactionTestType(CryptoTransactionTestType.SELL)
            .shouldSucceed(false)
            .build();
    cryptoTransactionTester.test(cryptoTransaction);
  }

  /**
   * Test simple buying of cryptocurrency
   */
  @Test
  public void testCryptoBuySimple() throws ScriptException {
    CryptoTransactionTester cryptoTransactionTester = CryptoTransactionTester.builder()
            .initialBalanceInDollars(1000)
            .initialCryptoBalance(Collections.singletonMap("ETH", 0.0))
            .build();

    cryptoTransactionTester.initialize();

    CryptoTransaction cryptoTransaction = CryptoTransaction.builder()
            .expectedEndingBalanceInDollars(900)
            .expectedEndingCryptoBalance(0.1)
            .cryptoPrice(1000)
            .cryptoAmountToTransact(0.1)
            .cryptoName("ETH")
            .cryptoTransactionTestType(CryptoTransactionTestType.BUY)
            .shouldSucceed(true)
            .build();
    cryptoTransactionTester.test(cryptoTransaction);
  }

  /**
   * Test simple selling of cryptocurrency
   */
  @Test
  public void testCryptoSellSimple() throws ScriptException {
    CryptoTransactionTester cryptoTransactionTester = CryptoTransactionTester.builder()
            .initialBalanceInDollars(1000)
            .initialCryptoBalance(Collections.singletonMap("ETH", 0.1))
            .build();

    cryptoTransactionTester.initialize();

    CryptoTransaction cryptoTransaction = CryptoTransaction.builder()
            .expectedEndingBalanceInDollars(1100)
            .expectedEndingCryptoBalance(0)
            .cryptoPrice(1000)
            .cryptoAmountToTransact(0.1)
            .cryptoName("ETH")
            .cryptoTransactionTestType(CryptoTransactionTestType.SELL)
            .shouldSucceed(true)
            .build();
    cryptoTransactionTester.test(cryptoTransaction);
  }

  /**
   * Test buying of cryptocurrency with an insufficient balance does not invoke a transaction
   */
  @Test
  public void testCryptoBuyInsufficientBalance() throws ScriptException {
    CryptoTransactionTester cryptoTransactionTester = CryptoTransactionTester.builder()
            .initialBalanceInDollars(1000)
            .build();

    cryptoTransactionTester.initialize();

    CryptoTransaction cryptoTransaction = CryptoTransaction.builder()
            .expectedEndingBalanceInDollars(1000)
            .cryptoPrice(1000)
            .cryptoAmountToTransact(10)
            .cryptoName("ETH")
            .cryptoTransactionTestType(CryptoTransactionTestType.BUY)
            .shouldSucceed(false)
            .build();
    cryptoTransactionTester.test(cryptoTransaction);
  }

  /**
   * Test that buying a negative amount of cryptocurrency does not invoke a transaction
   */
  @Test
  public void testCryptoBuyNegativeAmount() throws ScriptException {
    CryptoTransactionTester cryptoTransactionTester = CryptoTransactionTester.builder()
            .initialBalanceInDollars(1000)
            .build();

    cryptoTransactionTester.initialize();

    CryptoTransaction cryptoTransaction = CryptoTransaction.builder()
            .expectedEndingBalanceInDollars(1000)
            .cryptoPrice(1000)
            .cryptoAmountToTransact(-0.1)
            .cryptoName("ETH")
            .cryptoTransactionTestType(CryptoTransactionTestType.BUY)
            .shouldSucceed(false)
            .build();
    cryptoTransactionTester.test(cryptoTransaction);
  }

  /**
   * Test that selling a negative amount of cryptocurrency does not invoke a transaction
   */
  @Test
  public void testCryptoSellNegativeAmount() throws ScriptException {
    CryptoTransactionTester cryptoTransactionTester = CryptoTransactionTester.builder()
            .initialBalanceInDollars(1000)
            .initialCryptoBalance(Collections.singletonMap("ETH", 0.1))
            .build();

    cryptoTransactionTester.initialize();

    CryptoTransaction cryptoTransaction = CryptoTransaction.builder()
            .expectedEndingBalanceInDollars(1000)
            .expectedEndingCryptoBalance(0.1)
            .cryptoPrice(1000)
            .cryptoAmountToTransact(-0.1)
            .cryptoName("ETH")
            .cryptoTransactionTestType(CryptoTransactionTestType.SELL)
            .shouldSucceed(false)
            .build();
    cryptoTransactionTester.test(cryptoTransaction);
  }

  /**
   * Test that no buying should take place when user is under overdraft
   */
  @Test
  public void testCryptoBuyOverdraft() throws ScriptException {
    CryptoTransactionTester cryptoTransactionTester = CryptoTransactionTester.builder()
            .initialBalanceInDollars(1000)
            .initialOverdraftBalanceInDollars(100)
            .build();

    cryptoTransactionTester.initialize();

    CryptoTransaction cryptoTransaction = CryptoTransaction.builder()
            .expectedEndingBalanceInDollars(1000)
            .expectedEndingOverdraftBalanceInDollars(100)
            .cryptoPrice(1000)
            .cryptoAmountToTransact(0.1)
            .cryptoName("ETH")
            .cryptoTransactionTestType(CryptoTransactionTestType.BUY)
            .overdraftTransaction(true)
            .shouldSucceed(false)
            .build();
    cryptoTransactionTester.test(cryptoTransaction);
  }

  /**
   * Test that selling cryptocurrency first pays off overdraft balance
   */
  @Test
  public void testCryptoSellOverdraft() throws ScriptException {
    CryptoTransactionTester cryptoTransactionTester = CryptoTransactionTester.builder()
            .initialBalanceInDollars(1000)
            .initialOverdraftBalanceInDollars(50)
            .initialCryptoBalance(Collections.singletonMap("ETH", 0.15))
            .build();

    cryptoTransactionTester.initialize();

    CryptoTransaction cryptoTransaction = CryptoTransaction.builder()
            .initialOverdraftBalanceInDollars(50)
            .expectedEndingBalanceInDollars(1050)
            .expectedEndingCryptoBalance(0.05)
            .expectedEndingOverdraftBalanceInDollars(0)
            .cryptoPrice(1000)
            .cryptoAmountToTransact(0.1)
            .cryptoName("ETH")
            .cryptoTransactionTestType(CryptoTransactionTestType.SELL)
            .overdraftTransaction(true)
            .shouldSucceed(true)
            .build();
    cryptoTransactionTester.test(cryptoTransaction);
  }

  /**
   * Test that no buy transaction occurs when the cryptocurrency price cannot be obtained
   */
  @Test
  public void testCryptoBuyInvalidPrice() throws ScriptException {
    CryptoTransactionTester cryptoTransactionTester = CryptoTransactionTester.builder()
            .initialBalanceInDollars(1000)
            .initialCryptoBalance(Collections.singletonMap("ETH", 0.0))
            .build();

    cryptoTransactionTester.initialize();

    CryptoTransaction cryptoTransaction = CryptoTransaction.builder()
            .expectedEndingBalanceInDollars(1000)
            .expectedEndingCryptoBalance(0)
            .cryptoPrice(-1)
            .cryptoAmountToTransact(0.1)
            .cryptoName("ETH")
            .cryptoTransactionTestType(CryptoTransactionTestType.BUY)
            .shouldSucceed(false)
            .build();
    cryptoTransactionTester.test(cryptoTransaction);
  }

  /**
   * Test that no sell transaction occurs when the cryptocurrency price cannot be obtained
   */
  @Test
  public void testCryptoSellInvalidPrice() throws ScriptException {
    CryptoTransactionTester cryptoTransactionTester = CryptoTransactionTester.builder()
            .initialBalanceInDollars(1000)
            .initialCryptoBalance(Collections.singletonMap("ETH", 0.1))
            .build();

    cryptoTransactionTester.initialize();

    CryptoTransaction cryptoTransaction = CryptoTransaction.builder()
            .expectedEndingBalanceInDollars(1000)
            .expectedEndingCryptoBalance(0.1)
            .cryptoPrice(-1)
            .cryptoAmountToTransact(0.1)
            .cryptoName("ETH")
            .cryptoTransactionTestType(CryptoTransactionTestType.SELL)
            .shouldSucceed(false)
            .build();
    cryptoTransactionTester.test(cryptoTransaction);
  }

}