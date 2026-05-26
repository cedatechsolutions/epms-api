package com.cems.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.Map;

public class PaginatedResponse<T> {

    private final List<T> data;
    private final PaginationMeta meta;
    private final Map<String, String> links;

    public PaginatedResponse(Page<T> page, String path) {
        this.data = page.getContent();
        this.meta = new PaginationMeta(
                page.getTotalElements() == 0 ? 1 : page.getNumber() + 1,
                page.getNumberOfElements() == 0 ? null : page.getNumber() * page.getSize() + 1,
                page.getTotalPages() == 0 ? 1 : page.getTotalPages(),
                path,
                page.getSize(),
                page.getNumberOfElements() == 0 ? null : page.getNumber() * page.getSize() + page.getNumberOfElements(),
                page.getTotalElements());
        this.links = Map.of();
    }

    public List<T> getData() {
        return data;
    }

    public PaginationMeta getMeta() {
        return meta;
    }

    public Map<String, String> getLinks() {
        return links;
    }

    public record PaginationMeta(
            @JsonProperty("current_page")
            int currentPage,
            Integer from,
            @JsonProperty("last_page")
            int lastPage,
            String path,
            @JsonProperty("per_page")
            int perPage,
            Integer to,
            long total) {
    }
}
