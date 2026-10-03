package com.myexampleproject.common;
import com.myexampleproject.common.exception.*;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class ErrorContractTests {
    @Test void statusAndDomainCodeRemainIndependentAndUpstreamDetailsAreHidden() {
        var handler=new GlobalExceptionHandler();
        for(var status:new HttpStatus[]{HttpStatus.BAD_REQUEST,HttpStatus.UNAUTHORIZED,HttpStatus.FORBIDDEN,HttpStatus.NOT_FOUND,HttpStatus.CONFLICT,HttpStatus.PAYLOAD_TOO_LARGE,HttpStatus.SERVICE_UNAVAILABLE}) {
            var response=handler.business(new DomainException(status,"SAFE_DOMAIN_CODE","fixture provider-secret"),new MockHttpServletRequest("POST","/fixture"));
            assertThat(response.getStatusCode()).isEqualTo(status);
            assertThat(response.getBody().getCode()).isEqualTo("SAFE_DOMAIN_CODE");
            if(status.is5xxServerError()) assertThat(response.getBody().getMessage()).doesNotContain("provider-secret");
        }
    }
}
