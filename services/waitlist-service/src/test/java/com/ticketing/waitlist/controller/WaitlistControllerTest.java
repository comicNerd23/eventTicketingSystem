package com.ticketing.waitlist.controller;

import com.ticketing.waitlist.domain.WaitlistEntry;
import com.ticketing.waitlist.domain.WaitlistStatus;
import com.ticketing.waitlist.dto.JoinWaitlistRequest;
import com.ticketing.waitlist.dto.WaitlistEntryResponse;
import com.ticketing.waitlist.exception.AlreadyOnWaitlistException;
import com.ticketing.waitlist.exception.WaitlistEntryNotFoundException;
import com.ticketing.waitlist.service.WaitlistService;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(WaitlistController.class)
class WaitlistControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean WaitlistService waitlistService;

    private static final String USER_ID = "00000000-0000-0000-0000-000000000001";

    // ── POST /waitlist ────────────────────────────────────────────────────────

    @Test
    void join_validRequest_returns201WithWaitingEntry() throws Exception {
        UUID eventId = UUID.randomUUID();
        given(waitlistService.join(eq(eventId), any()))
            .willReturn(WaitlistEntryResponse.from(anEntry(UUID.randomUUID(), eventId, WaitlistStatus.WAITING), 1));

        JoinWaitlistRequest req = new JoinWaitlistRequest();
        req.setEventId(eventId);

        mvc.perform(post("/waitlist")
                .header("X-User-Id", USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("WAITING"))
            .andExpect(jsonPath("$.position").value(1));
    }

    @Test
    void join_whenAlreadyOnWaitlist_returns409() throws Exception {
        UUID eventId = UUID.randomUUID();
        given(waitlistService.join(eq(eventId), any()))
            .willThrow(new AlreadyOnWaitlistException(eventId, UUID.randomUUID()));

        JoinWaitlistRequest req = new JoinWaitlistRequest();
        req.setEventId(eventId);

        mvc.perform(post("/waitlist")
                .header("X-User-Id", USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isConflict());
    }

    @Test
    void join_missingEventId_returns400() throws Exception {
        mvc.perform(post("/waitlist")
                .header("X-User-Id", USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());
    }

    // ── GET /waitlist/{entryId} ───────────────────────────────────────────────

    @Test
    void getEntry_whenFound_returns200() throws Exception {
        UUID entryId = UUID.randomUUID();
        given(waitlistService.getEntry(entryId))
            .willReturn(WaitlistEntryResponse.from(anEntry(entryId, UUID.randomUUID(), WaitlistStatus.PROMOTED), 0));

        mvc.perform(get("/waitlist/{entryId}", entryId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PROMOTED"));
    }

    @Test
    void getEntry_whenNotFound_returns404() throws Exception {
        UUID entryId = UUID.randomUUID();
        given(waitlistService.getEntry(entryId)).willThrow(new WaitlistEntryNotFoundException(entryId));

        mvc.perform(get("/waitlist/{entryId}", entryId))
            .andExpect(status().isNotFound());
    }

    // ── DELETE /waitlist/{entryId} ────────────────────────────────────────────

    @Test
    void leave_whenFound_returns204() throws Exception {
        UUID entryId = UUID.randomUUID();

        mvc.perform(delete("/waitlist/{entryId}", entryId))
            .andExpect(status().isNoContent());

        then(waitlistService).should().leave(entryId);
    }

    @Test
    void leave_whenNotFound_returns404() throws Exception {
        UUID entryId = UUID.randomUUID();
        willThrow(new WaitlistEntryNotFoundException(entryId)).given(waitlistService).leave(entryId);

        mvc.perform(delete("/waitlist/{entryId}", entryId))
            .andExpect(status().isNotFound());
    }

    // ── GET /waitlist/events/{eventId}/me ─────────────────────────────────────

    @Test
    void getMyEntryForEvent_whenFound_returns200() throws Exception {
        UUID eventId = UUID.randomUUID();
        given(waitlistService.getMyEntryForEvent(eq(eventId), any()))
            .willReturn(WaitlistEntryResponse.from(anEntry(UUID.randomUUID(), eventId, WaitlistStatus.WAITING), 1));

        mvc.perform(get("/waitlist/events/{eventId}/me", eventId)
                .header("X-User-Id", USER_ID))
            .andExpect(status().isOk());
    }

    @Test
    void getMyEntryForEvent_whenNotFound_returns404() throws Exception {
        UUID eventId = UUID.randomUUID();
        given(waitlistService.getMyEntryForEvent(eq(eventId), any()))
            .willThrow(new WaitlistEntryNotFoundException(eventId));

        mvc.perform(get("/waitlist/events/{eventId}/me", eventId)
                .header("X-User-Id", USER_ID))
            .andExpect(status().isNotFound());
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private WaitlistEntry anEntry(UUID id, UUID eventId, WaitlistStatus status) {
        WaitlistEntry e = new WaitlistEntry();
        e.setId(id);
        e.setEventId(eventId);
        e.setEventTitle("Test Event");
        e.setUserId(UUID.fromString(USER_ID));
        e.setStatus(status);
        return e;
    }
}
