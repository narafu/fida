package com.fida.domain.port.out;

import com.fida.domain.model.ParsedOrder;

import java.util.List;

public interface OcrPort {
    ParsedOrder analyze(List<byte[]> images);
}
