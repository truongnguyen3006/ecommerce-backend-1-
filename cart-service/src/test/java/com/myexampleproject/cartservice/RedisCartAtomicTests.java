package com.myexampleproject.cartservice;
import com.myexampleproject.cartservice.service.*;
import com.myexampleproject.cartservice.repository.CartRepository;
import com.myexampleproject.common.client.ProductCatalogClient;
import com.myexampleproject.common.dto.CartItemRequest;
import com.myexampleproject.cartservice.model.CartItemEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.*;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
/** Own loopback Redis process, unique temporary data directory, no access to the owner's Redis. */
class RedisCartAtomicTests {
 @TempDir Path dir;Process process;int port;String executable;
 LettuceConnectionFactory connection;StringRedisTemplate strings;CartService service;
 PurchasedOrderClient orders=mock(PurchasedOrderClient.class);ProductCatalogClient catalog=mock(ProductCatalogClient.class);StockClient stock=mock(StockClient.class);
 @BeforeEach void start() throws Exception {
  executable=Objects.requireNonNullElse(System.getenv("TEST_REDIS_EXECUTABLE"),"/usr/bin/redis-server");
  try(var socket=new ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())) {port=socket.getLocalPort();}
  launch();connection=new LettuceConnectionFactory("127.0.0.1",port);connection.afterPropertiesSet();connection.start();
  strings=new StringRedisTemplate(connection);strings.afterPropertiesSet();awaitReady();
  when(catalog.find(anyString())).thenAnswer(i -> new ProductCatalogClient.CatalogItem(i.getArgument(0),"Fixture",BigDecimal.TEN,null,null,null,true));when(stock.quantity(anyString())).thenReturn(100);
  service=new CartService(mock(CartRepository.class),mock(RedisTemplate.class),strings,mock(KafkaTemplate.class),new ObjectMapper(),catalog,stock,orders);
 }
 void launch() throws Exception {process=new ProcessBuilder(executable,"--bind","127.0.0.1","--port",Integer.toString(port),"--dir",dir.toString(),"--save","","--appendonly","yes","--appendfsync","always","--protected-mode","yes").redirectErrorStream(true).redirectOutput(dir.resolve("server.log").toFile()).start();}
 void awaitReady() throws Exception {Exception last=null;for(int i=0;i<50;i++) {try(var c=connection.getConnection()) {if("PONG".equals(c.ping())) return;}catch(Exception ex){last=ex;}Thread.sleep(20);}throw new IllegalStateException("Disposable Redis did not start",last);}
 @AfterEach void stop() throws Exception {if(connection!=null) connection.destroy();if(process!=null) {process.destroy();if(!process.waitFor(3,TimeUnit.SECONDS)) process.destroyForcibly();}}
 CartItemEntity line(String owner,String sku) {return service.viewCart(owner).getItems().stream().filter(i -> i.getSkuCode().equals(sku)).findFirst().orElseThrow();}
 PurchasedCartRequest request(CartItemEntity line) {return new PurchasedCartRequest("order-fixture",List.of(new PurchasedCartRequest.Line(line.getSkuCode(),line.getQuantity(),line.getRevision())));}
 @Test void cleanupIsOwnerScopedConditionalAndIdempotent() {
  service.addItem("A",new CartItemRequest("SKU",1));service.addItem("B",new CartItemRequest("SKU",1));service.addItem("A",new CartItemRequest("NEW",1));var snapshot=line("A","SKU");
  assertThat(service.cleanupPurchased("A","fixture-token",request(snapshot))).isEqualTo(1);assertThat(service.viewCart("A").getItems()).extracting(CartItemEntity::getSkuCode).containsExactly("NEW");
  service.addItem("A",new CartItemRequest("SKU",1));assertThat(service.cleanupPurchased("A","fixture-token",request(snapshot))).isZero();assertThat(line("B","SKU").getQuantity()).isEqualTo(1);assertThat(line("A","SKU").getQuantity()).isEqualTo(1);
 }
 @Test void changingAwayAndBackToSameQuantityStillProtectsTheNewLine() {
  service.addItem("A",new CartItemRequest("SKU",1));var old=line("A","SKU");service.updateQuantity("A",new CartItemRequest("SKU",2));service.updateQuantity("A",new CartItemRequest("SKU",1));
  assertThat(service.cleanupPurchased("A","fixture-token",request(old))).isZero();assertThat(line("A","SKU").getRevision()).isNotEqualTo(old.getRevision());
 }
 @Test void concurrentMutationAndCleanupNeverDeleteTheLaterMutation() throws Exception {
  service.addItem("A",new CartItemRequest("SKU",1));var old=line("A","SKU");var start=new CountDownLatch(1);
  try(var pool=Executors.newFixedThreadPool(2)) {
   var mutation=pool.submit(() -> {start.await();service.updateQuantity("A",new CartItemRequest("SKU",2));return true;});
   var cleanup=pool.submit(() -> {start.await();return service.cleanupPurchased("A","fixture-token",request(old));});start.countDown();mutation.get(5,TimeUnit.SECONDS);cleanup.get(5,TimeUnit.SECONDS);
  }
  assertThat(line("A","SKU").getQuantity()).isEqualTo(2);
 }
 @Test void failureBeforeAcceptedOrderPreservesAllCartData() {
  service.addItem("A",new CartItemRequest("SKU",1));var old=line("A","SKU");doThrow(new ResponseStatusException(HttpStatus.CONFLICT,"Not accepted")).when(orders).verify(anyString(),anyString(),any());
  assertThatThrownBy(() -> service.cleanupPurchased("A","fixture-token",request(old))).isInstanceOf(ResponseStatusException.class);assertThat(line("A","SKU").getRevision()).isEqualTo(old.getRevision());
 }
 @Test void redisRestartPreservesRevisionAndCleanupReceipt() throws Exception {
  service.addItem("A",new CartItemRequest("SKU",1));var old=line("A","SKU");service.cleanupPurchased("A","fixture-token",request(old));service.addItem("A",new CartItemRequest("SKU",1));var newer=line("A","SKU");
  process.destroy();assertThat(process.waitFor(3,TimeUnit.SECONDS)).isTrue();launch();awaitReady();
  assertThat(line("A","SKU").getRevision()).isEqualTo(newer.getRevision());assertThat(service.cleanupPurchased("A","fixture-token",request(old))).isZero();assertThat(line("A","SKU").getQuantity()).isEqualTo(1);
 }
}
