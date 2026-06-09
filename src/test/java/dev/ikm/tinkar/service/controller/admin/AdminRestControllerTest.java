package dev.ikm.tinkar.service.controller.admin;

import dev.ikm.tinkar.service.dto.EntityCountSummaryResponse;
import dev.ikm.tinkar.service.dto.ReasonerResultsResponse;
import dev.ikm.tinkar.service.service.TinkarService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminRestControllerTest {

    @Mock
    private TinkarService tinkarService;

    @InjectMocks
    private AdminRestController controller;

    // -------------------------------------------------------------------------
    // importChangeset
    // -------------------------------------------------------------------------

    @Test
    void importChangeset_delegatesToServiceAndReturns200() throws Exception {
        // Arrange
        byte[] content = "fake-zip-bytes".getBytes();
        MockMultipartFile multipartFile = new MockMultipartFile(
                "file", "test.zip", "application/zip", content);

        EntityCountSummaryResponse mockResult = Mockito.mock(EntityCountSummaryResponse.class);
        when(tinkarService.importChangeset(any(File.class), anyBoolean())).thenReturn(mockResult);

        // Act
        ResponseEntity<EntityCountSummaryResponse> response =
                controller.importChangeset(multipartFile, true);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(mockResult);
        verify(tinkarService).importChangeset(any(File.class), anyBoolean());
    }

    @Test
    void importChangeset_withMultiPassFalse_passesFalseToService() throws Exception {
        // Arrange
        MockMultipartFile multipartFile = new MockMultipartFile(
                "file", "test.zip", "application/zip", new byte[]{1, 2, 3});

        EntityCountSummaryResponse mockResult = Mockito.mock(EntityCountSummaryResponse.class);
        when(tinkarService.importChangeset(any(File.class), anyBoolean())).thenReturn(mockResult);

        // Act
        ResponseEntity<EntityCountSummaryResponse> response =
                controller.importChangeset(multipartFile, false);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(tinkarService).importChangeset(any(File.class), Mockito.eq(false));
    }

    @Test
    void importChangeset_whenServiceThrows_returnsErrorResponse() throws Exception {
        // Arrange
        MockMultipartFile multipartFile = new MockMultipartFile(
                "file", "bad.zip", "application/zip", new byte[]{9});

        when(tinkarService.importChangeset(any(File.class), anyBoolean()))
                .thenThrow(new RuntimeException("disk full"));

        // Act
        ResponseEntity<EntityCountSummaryResponse> response =
                controller.importChangeset(multipartFile, true);

        // Assert — controller catches and wraps as EntityCountSummaryResponse.error(...)
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().errorMessage()).contains("disk full");
    }

    // -------------------------------------------------------------------------
    // runReasoner
    // -------------------------------------------------------------------------

    @Test
    void runReasoner_delegatesToServiceAndReturns200() {
        // Arrange
        ReasonerResultsResponse mockResult = Mockito.mock(ReasonerResultsResponse.class);
        when(tinkarService.runReasoner()).thenReturn(mockResult);

        // Act
        ResponseEntity<ReasonerResultsResponse> response = controller.runReasoner();

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(mockResult);
        verify(tinkarService).runReasoner();
    }
}
