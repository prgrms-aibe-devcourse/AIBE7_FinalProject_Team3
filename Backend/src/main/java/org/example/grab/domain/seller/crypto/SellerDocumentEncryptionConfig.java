package org.example.grab.domain.seller.crypto;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SellerDocumentEncryptionProperties.class)
public class SellerDocumentEncryptionConfig {
}
