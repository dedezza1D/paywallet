package br.com.paywallet.kyc;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import br.com.paywallet.kyc.KycService.DownloadUrl;
import br.com.paywallet.kyc.KycService.KycDocumentResponse;

@RestController
@RequestMapping("/users/{userId}/kyc-documents")
public class KycController {

    private final KycService service;

    public KycController(KycService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public KycDocumentResponse upload(@PathVariable Long userId,
                                      @RequestParam KycDocument.Type type,
                                      @RequestParam MultipartFile file) {
        return service.upload(userId, type, file);
    }

    @GetMapping
    public List<KycDocumentResponse> list(@PathVariable Long userId) {
        return service.list(userId);
    }

    @GetMapping("/{documentId}/download-url")
    public DownloadUrl downloadUrl(@PathVariable Long userId, @PathVariable UUID documentId) {
        return service.downloadUrl(userId, documentId);
    }
}
