package org.example.grab.domain.drop.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.grab.global.entity.UUIDEntity;

import java.util.UUID;

@Entity
@Table(name = "drop_images")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DropImage extends UUIDEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "drop_id", nullable = false)
    private Drop drop;

    @Column(name = "image_url", nullable = false, length = 500)
    private String imageUrl;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "alt_text", nullable = false, length = 300)
    private String altText;

    // 발급된 imageId를 public_id로 그대로 쓴다(IMAGE_UPLOAD.md 1.1).
    private DropImage(Drop drop, UUID imageId, String imageUrl, int sortOrder, String altText) {
        super(imageId);
        this.drop = drop;
        this.imageUrl = imageUrl;
        this.sortOrder = sortOrder;
        this.altText = altText;
    }

    public static DropImage create(Drop drop, UUID imageId, String imageUrl, int sortOrder, String altText) {
        return new DropImage(drop, imageId, imageUrl, sortOrder, altText);
    }
}
