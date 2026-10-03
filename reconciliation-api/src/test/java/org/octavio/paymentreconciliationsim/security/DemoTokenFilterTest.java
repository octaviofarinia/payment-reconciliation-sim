package org.octavio.paymentreconciliationsim.security;
import java.io.*;
import java.util.*;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.octavio.paymentreconciliationsim.http.ApiExceptionHandler;
import org.springframework.mock.web.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class DemoTokenFilterTest {
 final JsonMapper json=JsonMapper.builder().build();
 DemoTokenFilter filter(){return new DemoTokenFilter("demo-token","worker-token",json);}
 MockHttpServletRequest request(String method,String path,String authorization){
  var request=new MockHttpServletRequest(method,path);request.setServletPath(path);
  if(authorization!=null)request.addHeader("Authorization",authorization);return request;
 }
 @Test void configurationRequiresDistinctNonemptyPrintableTokens(){
  for(String token:Arrays.asList(null,""," ","line\nfeed","é","x".repeat(1025))){
   assertThrows(IllegalArgumentException.class,()->new DemoTokenFilter(token,"worker",json));
   assertThrows(IllegalArgumentException.class,()->new DemoTokenFilter("demo",token,json));
  }
  assertThrows(IllegalArgumentException.class,()->new DemoTokenFilter("same","same",json));
  assertDoesNotThrow(()->new DemoTokenFilter("!","~".repeat(1024),json));
 }
 @Test void routeBoundariesAndBothTokensAreEnforcedBeforeTheChain()throws Exception{
  for(String path:List.of("/api/v1","/api/v1/transactions","/internal/v1","/internal/v1/reconciliation-runs/a/input")){
   boolean worker=path.startsWith("/internal/");
   for(String authorization:Arrays.asList(null,"Basic demo-token","bearer demo-token","Bearer ","Bearer wrong","Bearer demo-token","Bearer worker-token")){
    var request=request("GET",path,authorization);var response=new MockHttpServletResponse();var chain=mock(FilterChain.class);
    filter().doFilterInternal(request,response,chain);
    int expected=authorization!=null&&authorization.equals("Bearer "+(worker?"worker-token":"demo-token"))?200:
     authorization!=null&&(authorization.equals("Bearer demo-token")||authorization.equals("Bearer worker-token"))?403:401;
    assertEquals(expected,response.getStatus());
    var correlation=(String)request.getAttribute(ApiExceptionHandler.CORRELATION_ID);assertDoesNotThrow(()->UUID.fromString(correlation));
    assertEquals(correlation,response.getHeader("X-Correlation-Id"));
    if(expected==200){verify(chain).doFilter(request,response);assertEquals("",response.getContentAsString());}
    else{verifyNoInteractions(chain);var body=json.readTree(response.getContentAsString());assertEquals(expected==401?"UNAUTHORIZED":"FORBIDDEN",body.path("code").asText());assertEquals(correlation,body.path("correlationId").asText());assertEquals("application/json",response.getContentType());assertEquals(expected==401?"Bearer":null,response.getHeader("WWW-Authenticate"));}
   }
  }
  for(String path:List.of("/swagger-ui/index.html","/v3/api-docs","/api/v10/route","/internal/v10/route")){
   var request=request("GET",path,null);var response=new MockHttpServletResponse();var chain=mock(FilterChain.class);
   filter().doFilterInternal(request,response,chain);verify(chain).doFilter(request,response);
  }
 }
 @Test void bodyBoundCountsActualBytesIndependentlyOfHeadersAndPreservesExactInput()throws Exception{
  String path="/internal/v1/reconciliation-runs/id/results";
  for(int length:new int[]{0,2,DemoTokenFilter.MAX_REPORT_BYTES,DemoTokenFilter.MAX_REPORT_BYTES+1}){
   var request=request("PUT",path,"Bearer worker-token");var bytes=new byte[length];Arrays.fill(bytes,(byte)'x');request.setContent(bytes);
   request.addHeader("Content-Length","1");var response=new MockHttpServletResponse();var calls=new ArrayList<byte[]>();
   FilterChain chain=(req,res)->calls.add(req.getInputStream().readAllBytes());
   filter().doFilterInternal(request,response,chain);
   if(length>DemoTokenFilter.MAX_REPORT_BYTES){assertEquals(413,response.getStatus());assertTrue(calls.isEmpty());assertEquals("PAYLOAD_TOO_LARGE",json.readTree(response.getContentAsString()).path("code").asText());}
   else{assertEquals(200,response.getStatus());assertEquals(1,calls.size());assertArrayEquals(bytes,calls.get(0));}
  }
  var req=request("POST",path,"Bearer worker-token");var response=new MockHttpServletResponse();var chain=mock(FilterChain.class);
  filter().doFilterInternal(req,response,chain);verify(chain).doFilter(req,response);
  req=request("PUT",path+"/more","Bearer worker-token");chain=mock(FilterChain.class);
  filter().doFilterInternal(req,response,chain);verify(chain).doFilter(req,response);
 }
 @Test void unreadableReportIsSanitizedAndNeverDelegated()throws Exception{
  var request=mock(HttpServletRequest.class);when(request.getServletPath()).thenReturn("/internal/v1/reconciliation-runs/id/results");when(request.getMethod()).thenReturn("PUT");when(request.getHeader("Authorization")).thenReturn("Bearer worker-token");
  when(request.getInputStream()).thenThrow(new IOException("secret credentials"));var response=new MockHttpServletResponse();var chain=mock(FilterChain.class);
  filter().doFilterInternal(request,response,chain);assertEquals(400,response.getStatus());verifyNoInteractions(chain);assertFalse(response.getContentAsString().contains("secret"));
 }
 @Test void bufferedStreamImplementsSynchronousServletInputAndUtf8Reader()throws Exception{
  var buffered=new DemoTokenFilter.BufferedRequest(request("PUT","/route",null),"é".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  assertEquals("é",buffered.getReader().readLine());var stream=buffered.getInputStream();assertTrue(stream.isReady());assertFalse(stream.isFinished());assertEquals(195,stream.read());assertEquals(169,stream.read());assertTrue(stream.isFinished());assertEquals(-1,stream.read());assertTrue(stream.isReady());
  assertThrows(UnsupportedOperationException.class,()->stream.setReadListener(mock(ReadListener.class)));
 }
}
