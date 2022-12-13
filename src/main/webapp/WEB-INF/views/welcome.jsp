<%@ page language="java" contentType="text/html; charset=ISO-8859-1"
    pageEncoding="ISO-8859-1"%>
<%@ taglib prefix="form" uri="http://www.springframework.org/tags/form" %>    
<!DOCTYPE html>
<html>
<head>
  <link rel="icon" href="https://fanapeel.com/wp-content/uploads/logo_-university-of-maryland-terrapins-testudo-turtle-hold-red-white-m.png">
  <meta charset="ISO-8859-1">
  <title>Welcome Page</title>
  <style type="text/css">
    label {
      display: inline-block;
      width: 200px;
      margin: 5px;
      text-align: left;
    }
    input[type=text], input[type=password], select {
      width: 200px;	
    }
    input[type=radio] {
      display: inline-block;
      margin-left: 45px;
    }
    
    input[type=checkbox] {
      display: inline-block;
      margin-right: 190px;
    }	
    
    button {
      padding: 10px;
      margin: 10px;
    }
  </style>
</head>

<body>
	<div align="center">
		<h2 style="margin-left: 4.5em;">Welcome to Testudo Bank!</h2>
    <img src="https://fanapeel.com/wp-content/uploads/logo_-university-of-maryland-terrapins-testudo-turtle-hold-red-white-m.png" style="float:left;width:100px;height:100px;">
		<a href='/login'>View Full Account</a> <br/>
		<a href='/checking_login'>View Checking Account</a> <br/>
		<a href='/savings_login'>View Savings Account</a> <br/>
    <a href='/deposit/checking'>Checking Deposit</a> <br/>
    <a href='/deposit/savings'>Savings Deposit</a> <br/>
    <a href='/withdraw/checking'>Checking Withdraw</a> <br/>
    <a href='/withdraw/savings'>Savings Withdraw</a> <br/>
    <a href='/dispute/checking' style="margin-left: 6em;">Checking Dispute</a> <br/>
    <a href='/dispute/savings' style="margin-left: 6em;">Savings Dispute</a> <br/>
    <a href='/transfer' style="margin-left: 6em;">Transfer</a> <br/>
    <a href='/buycrypto' style="margin-left: 6em;">Buy Cryptocurrency</a>
    <a href='/sellcrypto' style="margin-left: 6em;">Sell Cryptocurrency</a> <br/>
	</div>
</body>

</html>