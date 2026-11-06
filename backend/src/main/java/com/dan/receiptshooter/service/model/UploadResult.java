package com.dan.receiptshooter.service.model;

import com.dan.receiptshooter.dto.ReceiptApi;

/** Distinguishes a newly stored receipt from an identical retry. */
public record UploadResult(ReceiptApi.UploadAcknowledgement acknowledgement, boolean created) {}
