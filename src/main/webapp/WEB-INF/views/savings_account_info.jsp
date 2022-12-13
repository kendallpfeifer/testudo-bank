<%@ page language="java" contentType="text/html; charset=ISO-8859-1"
    pageEncoding="ISO-8859-1"%>  
<!DOCTYPE html>
<html>
<head>
  <link rel="icon" href="https://fanapeel.com/wp-content/uploads/logo_-university-of-maryland-terrapins-testudo-turtle-hold-red-white-m.png">
  <meta charset="ISO-8859-1">
  <title>${user.firstName} ${user.lastName} - Testudo Bank Savings Page</title>
  <style type="text/css">
    label {
      display: inline-block;
      width: 200px;
      margin: 5px;
      text-align: left;
    }
    button {
      padding: 10px;
      margin: 10px;
    }
    a.button {
      -webkit-appearance: button;
      -moz-appearance: button;
      appearance: button;

      text-decoration: none;
      color: initial;
    }
  </style>
</head>
<body>
	<div align="center">
	<h2><span>${user.firstName}</span> <span>${user.lastName}</span> Bank Account Info</h2>
    <span>Username: </span><span>${user.username}</span><br/>
	<span>Savings Balance: $</span><span>${user.savingsBalance}</span><br/>
    <span>Savings Overdraft Balance: $</span><span>${user.savingsOverDraftBalance}</span><br/>
    <span>Savings Re-payment logs: </span><span>${user.savingsLogs}</span><br/>
    <span>Savings Transaction History: </span><span>${user.savingsTransactionHist}</span><br/>
    <span>Transfer History: </span><span>${user.transferHist}</span><br/>
    <span>Internal Transfer History: </span><span>${user.internalTransferHist}</span><br/>
    <br/>
    <a href='/deposit/savings'>Deposit</a>
    <a href='/withdraw/savings'>Withdraw</a>
    <a href='/dispute/savings'>Dispute</a>
    <a href='/savings_transfer'>Transfer Money to Checking Account</a>
    <a href='/transfer'>Transfer</a>
    <a href='/'>Logout</a>
	</div>
</body>
</html>