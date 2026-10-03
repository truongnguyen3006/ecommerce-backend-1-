package com.myexampleproject.productservice;
import com.myexampleproject.productservice.service.CloudinaryImageService;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
class UploadEnvelopeTests {
    private MultipartFile image(long bytes) {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(bytes);
        when(file.getContentType()).thenReturn("image/png");
        return file;
    }
    @Test void oversizedBatchIsRejectedBeforeCredentialsOrExternalUploadAreUsed() {
        var service = new CloudinaryImageService();
        MultipartFile tenMiB = image(10L*1024*1024);
        assertThatThrownBy(() -> service.uploadMultipleImages(List.of(tenMiB,tenMiB,tenMiB,tenMiB,tenMiB)))
            .isInstanceOfSatisfying(ResponseStatusException.class, failure -> org.assertj.core.api.Assertions.assertThat(failure.getStatusCode().value()).isEqualTo(413));
        assertThatThrownBy(() -> service.uploadSingleImage(image(10L*1024*1024+1)))
            .isInstanceOfSatisfying(ResponseStatusException.class, failure -> org.assertj.core.api.Assertions.assertThat(failure.getStatusCode().value()).isEqualTo(413));
    }
    @Test void supportedSizeStillFailsSafelyWithoutCloudinaryCredentials() {
        assertThatThrownBy(() -> new CloudinaryImageService().uploadSingleImage(image(10L*1024*1024)))
            .isInstanceOfSatisfying(ResponseStatusException.class, failure -> org.assertj.core.api.Assertions.assertThat(failure.getStatusCode().value()).isEqualTo(409));
    }
}
