package com.ticketing.event.controller;

import tools.jackson.databind.ObjectMapper;
import com.ticketing.event.domain.Section;
import com.ticketing.event.domain.Venue;
import com.ticketing.event.dto.CreateSectionRequest;
import com.ticketing.event.dto.CreateVenueRequest;
import com.ticketing.event.dto.VenueResponse;
import com.ticketing.event.service.VenueService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(VenueController.class)
class VenueControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean VenueService venueService;

    @Test
    void createVenue_validRequest_returns201WithComputedCapacity() throws Exception {
        given(venueService.createVenue(any())).willReturn(VenueResponse.from(aVenue()));

        CreateVenueRequest req = new CreateVenueRequest();
        req.setName("The O2 Arena");
        req.setAddress("Peninsula Square");
        req.setCity("London");
        req.setCountry("UK");
        CreateSectionRequest section = new CreateSectionRequest();
        section.setName("Floor");
        section.setRows(10);
        section.setSeatsPerRow(20);
        section.setPriceGbp(89.5);
        req.setSections(List.of(section));

        mvc.perform(post("/venues")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.capacity").value(200))
            .andExpect(jsonPath("$.sections[0].name").value("Floor"));
    }

    @Test
    void createVenue_missingRequiredFields_returns400() throws Exception {
        mvc.perform(post("/venues")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Venue aVenue() {
        Venue v = new Venue();
        v.setId(UUID.randomUUID());
        v.setName("The O2 Arena");
        v.setAddress("Peninsula Square");
        v.setCity("London");
        v.setCountry("UK");
        v.setCapacity(200);

        Section s = new Section();
        s.setId(UUID.randomUUID());
        s.setVenue(v);
        s.setName("Floor");
        s.setRows(10);
        s.setSeatsPerRow(20);
        s.setPriceGbp(89.5);
        v.setSections(List.of(s));
        return v;
    }
}
