package com.myexampleproject.cartservice;
import com.myexampleproject.cartservice.service.*;
import com.myexampleproject.common.exception.DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.http.MediaType;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
class PurchasedOrderClientTests {
 RestTemplate rest=new RestTemplate();MockRestServiceServer mock=MockRestServiceServer.bindTo(rest).build();
 PurchasedOrderClient client=new PurchasedOrderClient(rest,"https://order.invalid");
 PurchasedCartRequest request(int quantity) {return new PurchasedCartRequest("fixture",List.of(new PurchasedCartRequest.Line("SKU",quantity,"revision")));}
 void order(String owner,String status) {mock.expect(requestTo("https://order.invalid/api/order/fixture")).andExpect(header("Authorization","Bearer fixture-token")).andRespond(withSuccess("{\"userId\":\""+owner+"\",\"status\":\""+status+"\",\"orderLineItemsList\":[{\"skuCode\":\"SKU\",\"quantity\":1}]}",MediaType.APPLICATION_JSON));}
 @Test void acceptedOwnedLinesAreVerifiedAgainstTheOrder() {order("A","COMPLETED");client.verify("A","fixture-token",request(1));mock.verify();}
 @Test void anotherOwnersOrderCannotAuthorizeCleanup() {order("B","COMPLETED");assertThatThrownBy(() -> client.verify("A","fixture-token",request(1))).isInstanceOfSatisfying(DomainException.class,e -> assertThat(e.getStatusCode().value()).isEqualTo(403));}
 @Test void unacceptedOrderCannotAuthorizeCleanup() {order("A","PENDING");assertThatThrownBy(() -> client.verify("A","fixture-token",request(1))).isInstanceOfSatisfying(DomainException.class,e -> assertThat(e.getCode()).isEqualTo("CHECKOUT_NOT_ACCEPTED"));}
 @Test void mismatchingPurchasedQuantityIsRejected() {order("A","COMPLETED");assertThatThrownBy(() -> client.verify("A","fixture-token",request(2))).isInstanceOfSatisfying(DomainException.class,e -> assertThat(e.getStatusCode().value()).isEqualTo(400));}
}
