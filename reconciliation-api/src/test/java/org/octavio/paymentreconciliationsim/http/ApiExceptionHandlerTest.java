package org.octavio.paymentreconciliationsim.http;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.octavio.paymentreconciliationsim.run.RecoveryService;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatusCode;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
class ApiExceptionHandlerTest {
 @Test void allStatusesHaveStableSanitizedErrorsWithTheSameCorrelationId(){
  var handler=new ApiExceptionHandler();var request=new MockHttpServletRequest();request.setAttribute(ApiExceptionHandler.CORRELATION_ID,"server-id");
  String[][] cases={{"400","INVALID_REQUEST","The request is invalid"},{"401","UNAUTHORIZED","A valid bearer token is required"},{"403","FORBIDDEN","The bearer token cannot access this route"},{"404","NOT_FOUND","The requested resource was not found"},{"405","METHOD_NOT_ALLOWED","The request method is unsupported"},{"406","NOT_ACCEPTABLE","The requested response format is unsupported"},{"409","CONFLICT","The request conflicts with the current resource state"},{"413","PAYLOAD_TOO_LARGE","The request payload exceeds the application limit"},{"415","UNSUPPORTED_MEDIA_TYPE","The request content type is unsupported"},{"503","SERVICE_UNAVAILABLE","The service is temporarily unavailable"},{"500","INTERNAL_ERROR","The request could not be completed"}};
  for(var entry:cases){int status=Integer.parseInt(entry[0]);var response=handler.handle(new ResponseStatusException(HttpStatusCode.valueOf(status),"secret stack credentials"),request);assertEquals(status,response.getStatusCode().value());assertEquals(new ApiError(entry[1],entry[2],"server-id"),response.getBody());}
  for(var failure:List.of(new org.springframework.http.converter.HttpMessageNotReadableException("secret",new org.springframework.mock.http.MockHttpInputMessage(new byte[0])),new IllegalArgumentException("secret"),new TypeMismatchException("secret",Integer.class))){var response=handler.handle(failure,request);assertEquals(400,response.getStatusCode().value());assertEquals(ApiExceptionHandler.error(400,"server-id"),response.getBody());}
  var response=handler.handle(new RuntimeException("secret"),request);assertEquals(500,response.getStatusCode().value());assertEquals(ApiExceptionHandler.error(500,"server-id"),response.getBody());
 }
 @Test void recoveryConflictsRetainTheirDistinctStableCodeAndExplanation(){
  var request=new MockHttpServletRequest();request.setAttribute(ApiExceptionHandler.CORRELATION_ID,"id");
  for(var entry:Map.of("UPLOAD_NEEDED","Upload needed: no verified retained settlement input","ORIGINAL_UNAVAILABLE","Original bound settlement version is unavailable; replacement is forbidden").entrySet()){
   var response=new ApiExceptionHandler().handle(new RecoveryService.RecoveryConflict(entry.getKey(),entry.getValue()),request);
   assertEquals(409,response.getStatusCode().value());assertEquals(new ApiError(entry.getKey(),entry.getValue(),"id"),response.getBody());
  }
 }
}
