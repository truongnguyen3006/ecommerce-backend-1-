package com.myexampleproject.cartservice;
import com.myexampleproject.cartservice.service.*;
import com.myexampleproject.cartservice.repository.CartRepository;
import com.myexampleproject.common.dto.CartItemRequest;
import com.myexampleproject.common.client.ProductCatalogClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.kafka.core.KafkaTemplate;
import java.math.BigDecimal;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class CartBusinessTests {
    StringRedisTemplate strings=mock(StringRedisTemplate.class);
    ProductCatalogClient catalog=mock(ProductCatalogClient.class);
    StockClient stock=mock(StockClient.class);
    KafkaTemplate kafka=mock(KafkaTemplate.class);
    CartService service=new CartService(mock(CartRepository.class),mock(RedisTemplate.class),strings,kafka,new ObjectMapper(),catalog,stock,mock(PurchasedOrderClient.class));
    @Test void invalidQuantityNeverCallsDownstreamServices() { assertThatThrownBy(() -> service.addItem("A",new CartItemRequest("SKU",-1))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);verifyNoInteractions(strings,catalog,stock,mock(PurchasedOrderClient.class)); }
    @Test void validAddUsesAuthoritativeCatalogAndAtomicCumulativeStockLimit() {
        when(catalog.find("SKU")).thenReturn(new ProductCatalogClient.CatalogItem("SKU","Product",BigDecimal.TEN,null,null,null,true));when(stock.quantity("SKU")).thenReturn(3);
        when(strings.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenReturn(2L);
        service.addItem("A",new CartItemRequest("SKU",2));verify(stock).quantity("SKU");
    }
    @Test void stockOverflowIsReportedAsConflict() {
        when(catalog.find("SKU")).thenReturn(new ProductCatalogClient.CatalogItem("SKU","Product",BigDecimal.TEN,null,null,null,true));when(stock.quantity("SKU")).thenReturn(3);
        when(strings.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenReturn(-1L);
        assertThatThrownBy(() -> service.addItem("A",new CartItemRequest("SKU",4))).isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,ex -> assertThat(ex.getStatusCode().value()).isEqualTo(409));
    }
    @Test void simultaneousCheckoutIsNotAcceptedBeforeTheFirstSnapshotIsPublished() {
        ValueOperations<String,String> values=mock(ValueOperations.class);when(strings.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(),anyString(),any(java.time.Duration.class))).thenReturn(false);
        when(values.get("cart:checkout:A")).thenReturn("EXISTING");
        assertThatThrownBy(() -> service.checkoutAsync("A")).isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,ex -> assertThat(ex.getStatusCode().value()).isEqualTo(409));
        verifyNoInteractions(kafka);verify(strings,never()).execute(any(RedisScript.class),anyList(),any(Object[].class));
    }
    @Test void deletedProductRemainsVisibleForRemovalButCannotBeCheckedOut() {
        HashOperations<String,Object,Object> hashes=mock(HashOperations.class);when(strings.opsForHash()).thenReturn(hashes);
        when(strings.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenReturn("[{\"skuCode\":\"SKU\",\"quantity\":1,\"revision\":\"r1\"}]");
        when(hashes.get("cart:data:A","SKU")).thenReturn("{\"name\":\"Saved item\",\"price\":10}");
        when(catalog.find("SKU")).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"Unavailable"));
        assertThat(service.viewCart("A").getItems().getFirst().getProductName()).isEqualTo("Saved item");
        ValueOperations<String,String> values=mock(ValueOperations.class);when(strings.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(),anyString(),any(java.time.Duration.class))).thenReturn(true);
        assertThatThrownBy(() -> service.checkoutAsync("A")).isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,ex -> assertThat(ex.getStatusCode().value()).isEqualTo(404));
        verifyNoInteractions(kafka);
    }
}
