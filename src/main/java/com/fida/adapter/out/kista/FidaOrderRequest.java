package com.fida.adapter.out.kista;

import java.math.BigDecimal;

record FidaOrderRequest(String symbol, String direction, Integer qty, BigDecimal price) {}
