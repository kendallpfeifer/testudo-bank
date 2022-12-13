<%@ page language="java" contentType="text/html; charset=ISO-8859-1"
    pageEncoding="ISO-8859-1"%>
<%@ taglib prefix="form" uri="http://www.springframework.org/tags/form" %>    
<!DOCTYPE html>
<html>
<head>
  <link rel="icon" href="https://fanapeel.com/wp-content/uploads/logo_-university-of-maryland-terrapins-testudo-turtle-hold-red-white-m.png">
  <meta charset="ISO-8859-1">
  <title>Deposit Form</title>
  <style type="text/css">

    select {
        font-size: .9rem;
        padding: 2px 5px;
    }

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
		<form:form action="transfer" method="post" modelAttribute="user">
			<form:label path="username">Username:</form:label>
			<form:input path="username"/><br/>
			
			<form:label path="password">Password:</form:label>
			<form:password path="password"/><br/>		

      <form:label path="transferRecipientID">Username of Recipient:</form:label>
			<form:input path="transferRecipientID"/><br/>

      <form:label style="margin-left: 2.5em;padding: 0 7em 2em 0;border-width: 2px;" 
        path="transferSenderAccountType">Account Type to Transfer From: </form:label>
      <select name="transferSenderAccountType" id = "transferSenderAccountType">
        <option value="savings">Savings</option>
        <option value="checking">Checking</option>
      </select>

      <br>

      <form:label style="margin-left: 2.5em;padding: 0 7em 2em 0;border-width: 2px;" 
      path="transferRecipientAccountType">Account Type to Transfer To: </form:label>
      <select name="transferRecipientAccountType" id = "transferRecipientAccountType">
        <option value="savings">Savings</option>
        <option value="checking">Checking</option>
      </select>
      
      <br>

      <form:label path="amountToTransfer">Amount to Transfer ($):</form:label>
			<form:input path="amountToTransfer"/><br/>	
				
			<form:button>Transfer</form:button>
		</form:form>
    <a href='/'>Home</a>
	</div>
</body>
</html>