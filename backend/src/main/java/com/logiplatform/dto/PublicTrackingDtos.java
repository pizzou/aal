package com.logiplatform.dto;

import java.time.Instant;
import java.util.List;

public final class PublicTrackingDtos {
        private PublicTrackingDtos() {
        }

        public record PublicShipmentView(
                        String referenceCode,
                        String originAddress,
                        String destinationAddress,
                        String status,
                        String transportMode,
                        String carrierName,
                        String carrierReferenceNumber,
                        Instant eta,
                        List<PublicTrackingEvent> events,
                        List<PublicDocument> documents,
                        PublicPod pod) {
        }

        public record PublicTrackingEvent(String eventType, String location, String notes, Instant occurredAt) {
        }

        public record PublicDocument(String documentType, String status, Instant createdAt) {
        }

        public record PublicPod(String recipientName, Instant deliveredAt, boolean evidenceAvailable) {
        }

        public record PublicFeedbackRequest(
                Integer rating,
                String category,
                String comment,
                String contactEmail) {
        }
}
