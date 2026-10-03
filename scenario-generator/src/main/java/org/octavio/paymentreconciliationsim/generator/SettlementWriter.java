package org.octavio.paymentreconciliationsim.generator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import static org.octavio.paymentreconciliationsim.generator.GeneratorContracts.*;
public final class SettlementWriter {
 public byte[] write(Scenario scenario){
  var csv=new StringBuilder("business_date,transaction_reference,amount_centavos,currency\n");
  for(var row:scenario.settlement())csv.append(scenario.businessDate()).append(',').append(row.reference()).append(',').append(row.amountCentavos()).append(",ARS\n");
  return csv.toString().getBytes(StandardCharsets.UTF_8);
 }
 public String sha256(byte[] bytes){
  try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
  catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
 }
}
