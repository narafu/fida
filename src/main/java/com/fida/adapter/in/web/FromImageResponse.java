package com.fida.adapter.in.web;

import java.util.UUID;

record FromImageResponse(UUID kistaId) {
    static FromImageResponse from(UUID id) {
        return new FromImageResponse(id);
    }
}
