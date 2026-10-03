package org.octavio.paymentreconciliationsim.generator;
import java.io.*;import java.nio.file.*;import java.net.*;import java.net.http.*;import java.util.*;
import com.sun.net.httpserver.HttpServer;import org.junit.jupiter.api.*;import static org.junit.jupiter.api.Assertions.*;
class GeneratorCliTest {
 ByteArrayOutputStream output=new ByteArrayOutputStream(),error=new ByteArrayOutputStream();
 String[] args={"--base-url","http://127.0.0.1:1234","--business-date","2026-10-01","--seed","42","--scenario","canonical"};
 Map<String,String> env=Map.of("RECONCILIATION_DEMO_TOKEN","secret");
 int run(String[] arguments,Map<String,String> environment){return GeneratorCli.run(arguments,environment,new PrintStream(output),new PrintStream(error));}
 @Test void rejectsInvalidArgumentsWithoutNetwork(){
  for(String[] invalid:List.of(new String[]{},new String[]{"--base-url"},new String[]{"--unknown","x"},new String[]{"--seed","1","--seed","2"})){assertEquals(2,run(invalid,env));assertTrue(error.toString().contains("Usage:"));}
  for(String[] replacement:List.of(new String[]{"--business-date","not-a-date"},new String[]{"--seed","1.2"},new String[]{"--scenario","unsupported"},new String[]{"--base-url","ftp://example.com"},new String[]{"--base-url","http:///missing"},new String[]{"--base-url","http://user:pass@example.com"},new String[]{"--base-url","http://example.com/a"},new String[]{"--base-url","http://example.com?secret"},new String[]{"--base-url","http://example.com#fragment"})){
   var invalid=args.clone();for(int i=0;i<invalid.length;i+=2)if(invalid[i].equals(replacement[0]))invalid[i+1]=replacement[1];assertEquals(2,run(invalid,env));
  }
  for(var bad:List.of(Map.<String,String>of(),Map.of("RECONCILIATION_DEMO_TOKEN",""),Map.of("RECONCILIATION_DEMO_TOKEN","two words"),Map.of("RECONCILIATION_DEMO_TOKEN","\n"))){assertEquals(2,run(args,bad));}
 }
 @Test void cliReportsApiConflictAndMismatchNonzeroAndSuccess()throws Exception{
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var json=tools.jackson.databind.json.JsonMapper.builder().build();String expected=Files.readString(Path.of("src/main/resources/scenarios/canonical-expected.json"));var expectedNode=json.readTree(expected);int[] conflict={409};
  server.createContext("/",e->{String body="{}",path=e.getRequestURI().getPath();int status=conflict[0];
   if(status==200){if(path.equals("/api/v1/reconciliation-runs"))body="{\"runId\":\"cli-run\",\"status\":\"COMPLETED\",\"created\":false}";
    else if(path.endsWith("/results"))body="{\"page\":0,\"size\":100,\"totalResults\":5,\"results\":"+expectedNode.get("results")+"}";
    else if(path.endsWith("/cli-run"))body="{\"runId\":\"cli-run\",\"status\":\"COMPLETED\",\"summary\":"+expectedNode.get("summary")+"}";
   }byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);e.sendResponseHeaders(status,bytes.length);e.getResponseBody().write(bytes);e.close();});server.start();
  try{
   var actual=args.clone();actual[1]="http://127.0.0.1:"+server.getAddress().getPort();
   assertEquals(1,run(actual,env));assertTrue(error.toString().contains("API HTTP 409"));assertFalse(error.toString().contains("secret"));
   conflict[0]=200;assertEquals(0,run(actual,env));assertTrue(output.toString().contains("Verified runId=cli-run"));
   var wrong=Files.createTempFile("generator-wrong",".json");Files.writeString(wrong,expected.replace("\"MATCHED\": 1","\"MATCHED\": 2"));var override=Arrays.copyOf(actual,actual.length+2);override[actual.length]="--expected";override[actual.length+1]=wrong.toString();
   try{assertEquals(1,run(override,env));assertTrue(error.toString().contains("runId=cli-run"));}finally{Files.delete(wrong);}
   override[actual.length+1]="/not-present-fixture.json";assertEquals(1,run(override,env));
  }finally{server.stop(0);}
 }
 @Test void realMainReturnsNonzeroForInvalidArguments()throws Exception{
  var command=new ArrayList<String>();command.add(Path.of(System.getProperty("java.home"),"bin","java").toString());
  java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().stream().filter(a->a.startsWith("-javaagent:")&&a.contains("jacoco")).forEach(command::add);
  command.addAll(List.of("-cp",System.getProperty("java.class.path"),ScenarioGeneratorMain.class.getName()));
  var process=new ProcessBuilder(command).redirectErrorStream(true).start();String response=new String(process.getInputStream().readAllBytes());assertEquals(2,process.waitFor());assertTrue(response.contains("Usage:"));
 }

 @Test void allMatchedOptionIsAcceptedAndDefaultPollingUsesDelay()throws Exception{
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);int[] count={0};var json=tools.jackson.databind.json.JsonMapper.builder().build();var expected=json.readTree(Files.readString(Path.of("src/main/resources/scenarios/canonical-expected.json")));
  server.createContext("/",e->{String body="{}",path=e.getRequestURI().getPath();
   if(path.equals("/api/v1/reconciliation-runs"))body="{\"runId\":\"cli-run\",\"status\":\"COMPLETED\",\"created\":false}";
   else if(path.endsWith("/results"))body="{\"page\":0,\"size\":100,\"totalResults\":5,\"results\":"+expected.get("results")+"}";
   else if(path.endsWith("/cli-run"))body=count[0]++==0?"{\"runId\":\"cli-run\",\"status\":\"PROCESSING\"}":"{\"runId\":\"cli-run\",\"status\":\"COMPLETED\",\"summary\":"+expected.get("summary")+"}";
   byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();});server.start();
  try{
   var actual=args.clone();actual[1]="http://127.0.0.1:"+server.getAddress().getPort();assertEquals(0,run(actual,env));
   actual[7]="all-matched";assertEquals(1,run(actual,env));assertTrue(error.toString().contains("Expected business output mismatch"));
  }finally{server.stop(0);}
 }
}
