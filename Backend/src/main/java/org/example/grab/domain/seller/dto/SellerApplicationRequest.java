package org.example.grab.domain.seller.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.example.grab.domain.seller.entity.DocumentType;
import org.example.grab.domain.seller.validation.ValidBusinessRegistrationNumber;

import java.util.Map;
import java.util.UUID;

public record SellerApplicationRequest(
        @NotBlank @Size(max = 100) String brandName,
        @NotBlank @Email @Size(max = 254) String contactEmail,
        @NotBlank @ValidBusinessRegistrationNumber String businessRegistrationNumber,
        String description,
        // 1.1절에서 발급받은 documentId. 4종(DocumentType) 모두 채워야 한다
        @NotEmpty Map<DocumentType, UUID> documentIds
) {
}
