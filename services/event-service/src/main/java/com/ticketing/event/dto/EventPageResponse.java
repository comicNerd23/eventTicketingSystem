package com.ticketing.event.dto;

import org.springframework.data.domain.Page;

import java.util.List;

public class EventPageResponse {

    private List<EventResponse> content;
    private long totalElements;
    private int totalPages;
    private int page;
    private int size;

    public static EventPageResponse from(Page<com.ticketing.event.domain.Event> page) {
        EventPageResponse r = new EventPageResponse();
        r.content = page.getContent().stream().map(EventResponse::from).toList();
        r.totalElements = page.getTotalElements();
        r.totalPages = page.getTotalPages();
        r.page = page.getNumber();
        r.size = page.getSize();
        return r;
    }

    public List<EventResponse> getContent() { return content; }
    public long getTotalElements() { return totalElements; }
    public int getTotalPages() { return totalPages; }
    public int getPage() { return page; }
    public int getSize() { return size; }
}
