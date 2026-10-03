package org.octavio.paymentreconciliationsim.generator;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;
import static org.octavio.paymentreconciliationsim.generator.GeneratorContracts.*;
/** Standalone CLI. Token is read only from RECONCILIATION_DEMO_TOKEN. */
public interface GeneratorCli {
 public static int run(String[] args,Map<String,String> environment,PrintStream out,PrintStream err){
  Map<String,String> options;URI base;LocalDate date;long seed;ScenarioKind kind;String token;
  try{
   options=parse(args);base=URI.create(required(options,"--base-url"));
   if(!Set.of("http","https").contains(base.getScheme()) || base.getHost()==null || base.getUserInfo()!=null || base.getRawQuery()!=null || base.getRawFragment()!=null || !Set.of("","/").contains(base.getPath()))throw new IllegalArgumentException();
   date=LocalDate.parse(required(options,"--business-date"));seed=Long.parseLong(required(options,"--seed"));
   kind=switch(required(options,"--scenario")){case "canonical"->ScenarioKind.CANONICAL;case "all-matched"->ScenarioKind.ALL_MATCHED;default->throw new IllegalArgumentException();};
   token=environment.get("RECONCILIATION_DEMO_TOKEN");if(token==null || !token.matches("[!-~]{1,1024}"))throw new IllegalArgumentException();
  }catch(RuntimeException invalid){err.println("Usage: --base-url URL --business-date YYYY-MM-DD --seed INTEGER --scenario canonical|all-matched [--expected FILE]; set RECONCILIATION_DEMO_TOKEN");return 2;}
  try(var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()){
   var factory=new ScenarioFactory();var scenario=factory.generate(seed,date,kind);
   ExpectedReport expected;
   if(options.containsKey("--expected")){try(var input=Files.newInputStream(Path.of(options.get("--expected")))){expected=JsonMapper.builder().build().readValue(input,ExpectedReport.class);}}
   else expected=factory.expected(scenario,kind);
   var verified=new GenerationWorkflow(new SimulatorClient(base,token,http),Clock.systemUTC(),Thread::sleep,Duration.ofMinutes(5)).run(scenario,expected);
   out.println("Verified runId="+verified.runId()+" results="+verified.report().results().size());return 0;
  }catch(Exception failure){
   String detail=failure instanceof IllegalStateException?failure.getMessage():"Generator failed ("+failure.getClass().getSimpleName()+")";
   err.println(detail);return 1;
  }
 }
 private static Map<String,String> parse(String[] args){
  if(args.length%2!=0)throw new IllegalArgumentException();
  var result=new HashMap<String,String>();
  for(int i=0;i<args.length;i+=2){
   if(!Set.of("--base-url","--business-date","--seed","--scenario","--expected").contains(args[i]) || result.putIfAbsent(args[i],args[i+1])!=null)throw new IllegalArgumentException();
  }
  return result;
 }
 private static String required(Map<String,String> options,String name){return Objects.requireNonNull(options.get(name));}
}
