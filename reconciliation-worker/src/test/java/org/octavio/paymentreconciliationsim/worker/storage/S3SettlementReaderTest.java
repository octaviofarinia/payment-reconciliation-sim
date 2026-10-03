package org.octavio.paymentreconciliationsim.worker.storage;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.*;
import org.octavio.paymentreconciliationsim.worker.event.ObjectReference;
import org.octavio.paymentreconciliationsim.worker.InputRejected;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class S3SettlementReaderTest {
 final S3Client s3=mock(S3Client.class);final S3SettlementReader reader=new S3SettlementReader(s3);
 final ObjectReference ref=new ObjectReference("run","bucket","key","exact-version");
 byte[] bytes="abc".getBytes();String hash="ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
 void setup(Long headSize,Long getSize,String headVersion,String getVersion,String headChecksum,String getChecksum,InputStream stream){
  when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().contentLength(headSize).versionId(headVersion).checksumSHA256(headChecksum).build());
  when(s3.getObject(any(GetObjectRequest.class))).thenReturn(new ResponseInputStream<>(GetObjectResponse.builder().contentLength(getSize).versionId(getVersion).checksumSHA256(getChecksum).build(),stream));
 }
 void normal(){setup(3L,3L,"exact-version","exact-version",null,null,new ByteArrayInputStream(bytes));}
 @Test void headAndGetUseOnlyExactVersionAndChecksumMode()throws Exception{
  String sum=Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));
  setup(3L,3L,"exact-version","exact-version",sum,sum,new ByteArrayInputStream(bytes));
  assertArrayEquals(bytes,reader.read(ref,3,hash));
  var head=org.mockito.ArgumentCaptor.forClass(HeadObjectRequest.class);verify(s3).headObject(head.capture());
  var get=org.mockito.ArgumentCaptor.forClass(GetObjectRequest.class);verify(s3).getObject(get.capture());
  assertEquals("bucket",head.getValue().bucket());assertEquals("key",head.getValue().key());assertEquals("exact-version",head.getValue().versionId());assertEquals(ChecksumMode.ENABLED,head.getValue().checksumMode());
  assertEquals("bucket",get.getValue().bucket());assertEquals("key",get.getValue().key());assertEquals("exact-version",get.getValue().versionId());assertEquals(ChecksumMode.ENABLED,get.getValue().checksumMode());
 }
 @Test void optionalResponseEvidenceDoesNotReplaceRawByteVerification()throws Exception{
  setup(3L,3L,null,null,null,null,new ByteArrayInputStream(bytes));assertArrayEquals(bytes,reader.read(ref,3,hash));
  for(String stage:List.of("head","get")){
   setup(3L,3L,stage.equals("head")?"other":null,stage.equals("get")?"other":null,null,null,new ByteArrayInputStream(bytes));
   assertEquals("OBJECT_VERSION_MISMATCH",assertThrows(InputRejected.class,()->reader.read(ref,3,hash)).code());
   setup(3L,3L,null,null,stage.equals("head")?"wrong":null,stage.equals("get")?"wrong":null,new ByteArrayInputStream(bytes));
   assertEquals("OBJECT_CHECKSUM_MISMATCH",assertThrows(InputRejected.class,()->reader.read(ref,3,hash)).code());
  }
  normal();assertEquals("OBJECT_CHECKSUM_MISMATCH",assertThrows(InputRejected.class,()->reader.read(ref,3,"a".repeat(64))).code());
 }
 @Test void sizeDisagreementAndOverflowNeverReturnPartialBytes(){
  for(long expected:new long[]{0,2097153,4}){normal();assertEquals("OBJECT_SIZE_MISMATCH",assertThrows(InputRejected.class,()->reader.read(ref,expected,hash)).code());}
  setup(null,3L,null,null,null,null,new ByteArrayInputStream(bytes));assertThrows(InputRejected.class,()->reader.read(ref,3,hash));
  setup(3L,null,null,null,null,null,new ByteArrayInputStream(bytes));assertThrows(InputRejected.class,()->reader.read(ref,3,hash));
  setup(3L,4L,null,null,null,null,new ByteArrayInputStream(bytes));assertThrows(InputRejected.class,()->reader.read(ref,3,hash));
  setup(3L,3L,null,null,null,null,new ByteArrayInputStream(new byte[]{1,2}));assertThrows(InputRejected.class,()->reader.read(ref,3,hash));
  setup(3L,3L,null,null,null,null,new ByteArrayInputStream(new byte[]{1,2,3,4}));assertThrows(InputRejected.class,()->reader.read(ref,3,hash));
 }
 @Test void exactByteLimitWorksAndOverflowProbeRejectsWithoutUnboundedAllocation()throws Exception{
  byte[] max=new byte[2097152];String maxHash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(max));
  setup(2097152L,2097152L,null,null,null,null,new ByteArrayInputStream(max));assertArrayEquals(max,reader.read(ref,2097152,maxHash));
  setup(2097152L,2097152L,null,null,null,null,new ByteArrayInputStream(new byte[2097153]));assertThrows(InputRejected.class,()->reader.read(ref,2097152,maxHash));
 }
 @Test void genuinelyBrokenStreamPropagatesOriginalIoFailureAndClosesStream(){
  var failure=new IOException("transport");var stream=new InputStream(){public int read()throws IOException{throw failure;}};
  var close=spy(stream);setup(3L,3L,null,null,null,null,close);
  assertSame(failure,assertThrows(IOException.class,()->reader.read(ref,3,hash)));assertDoesNotThrow(()->verify(close).close());
  when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(503).build());
  assertThrows(S3Exception.class,()->reader.read(ref,3,hash));
 }
}
