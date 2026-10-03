package com.myexampleproject.cartservice.service;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import com.myexampleproject.common.exception.DomainException;
import java.util.*;
@Component
public class PurchasedOrderClient {
    private final RestTemplate client;
    private final String baseUrl;
    public PurchasedOrderClient(RestTemplate client,@Value("${order.service.base-url:http://localhost:8083}") String baseUrl) {this.client=client;this.baseUrl=baseUrl;}
    public void verify(String owner,String token,PurchasedCartRequest request) {
        if(request==null || request.items()==null || request.items().isEmpty()) throw new DomainException(HttpStatus.BAD_REQUEST,"INVALID_CART_SNAPSHOT","Purchased lines required");
        JsonNode order;
        try {
            HttpHeaders headers=new HttpHeaders();headers.setBearerAuth(token);
            order=client.exchange(baseUrl+"/api/order/{number}",HttpMethod.GET,new HttpEntity<>(headers),JsonNode.class,request.orderNumber()).getBody();
        } catch(HttpClientErrorException ex) {throw new DomainException(HttpStatus.valueOf(ex.getStatusCode().value()),"ORDER_ACCESS_DENIED","Order unavailable for this owner");}
          catch(RestClientException ex) {throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE,"ORDER_UNAVAILABLE","Order service unavailable");}
        if(order==null || !owner.equals(order.path("userId").asText())) throw new DomainException(HttpStatus.FORBIDDEN,"ORDER_ACCESS_DENIED","Order does not belong to this owner");
        if(!Set.of("VALIDATED","COMPLETED").contains(order.path("status").asText())) throw new DomainException(HttpStatus.CONFLICT,"CHECKOUT_NOT_ACCEPTED","Order has not accepted these lines");
        Map<String,Integer> bought=new HashMap<>();
        for(var line:order.path("orderLineItemsList")) bought.merge(line.path("skuCode").asText(),line.path("quantity").asInt(),Integer::sum);
        Set<String> unique=new HashSet<>();
        for(var item:request.items()) if(!unique.add(item.skuCode()) || !Objects.equals(bought.get(item.skuCode()),item.quantity()))
            throw new DomainException(HttpStatus.BAD_REQUEST,"INVALID_CART_SNAPSHOT","Cleanup must match purchased quantities");
    }
}
