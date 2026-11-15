package com.fida.adapter.out.kista;

import java.util.UUID;

// KISTA 응답에서 저장 ID만 읽는다 (나머지 필드는 무시)
record KistaOrderResponse(UUID id) {}
