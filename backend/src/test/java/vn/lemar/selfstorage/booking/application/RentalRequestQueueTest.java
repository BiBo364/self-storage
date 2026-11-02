package vn.lemar.selfstorage.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import vn.lemar.selfstorage.booking.application.dto.RentalRequestPageResponse;
import vn.lemar.selfstorage.booking.domain.RentalRequest;
import vn.lemar.selfstorage.booking.domain.RentalRequestStatus;
import vn.lemar.selfstorage.booking.repository.BookingReadRepository;
import vn.lemar.selfstorage.booking.repository.RentalRequestRepository;
import vn.lemar.selfstorage.facility.application.FacilityAccess;
import vn.lemar.selfstorage.pricing.application.PricingQuery;
import vn.lemar.selfstorage.pricing.application.UnitTypeQueryService;

@ExtendWith(MockitoExtension.class)
class RentalRequestQueueTest {

    @Mock
    private RentalRequestRepository repository;

    @Mock
    private BookingReadRepository bookingReadRepository;

    @Mock
    private FacilityAccess facilityAccess;

    @Mock
    private UnitTypeQueryService unitTypeQueryService;

    @Mock
    private PricingQuery pricingQuery;

    @InjectMocks
    private RentalRequestService service;

    @Test
    void queueFiltersByFacilityAndStatusNewestFirst() {
        when(facilityAccess.exists(1L)).thenReturn(true);
        when(repository.findByFacilityIdAndStatus(eq(1L), eq(RentalRequestStatus.Pending),
                any(PageRequest.class))).thenReturn(new PageImpl<>(List.of()));

        RentalRequestPageResponse page = service.queue(1L, RentalRequestStatus.Pending, 0, 20);

        ArgumentCaptor<PageRequest> captor = ArgumentCaptor.forClass(PageRequest.class);
        verify(repository).findByFacilityIdAndStatus(eq(1L), eq(RentalRequestStatus.Pending),
                captor.capture());
        assertThat(captor.getValue().getSort().getOrderFor("createdAt").getDirection().name())
                .isEqualTo("DESC");
        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(20);
        assertThat(page.total()).isZero();
    }

    @Test
    void queueWithoutStatusReturnsAllOfFacility() {
        when(facilityAccess.exists(1L)).thenReturn(true);
        when(repository.findByFacilityId(eq(1L), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of()));

        RentalRequestPageResponse page = service.queue(1L, null, 1, 10);

        assertThat(page.page()).isEqualTo(1);
        assertThat(page.total()).isZero();
    }

    @Test
    void queueMarksReproposeWhenLastProposalRejected() {
        RentalRequest request = request(RentalRequestStatus.Converted);
        when(facilityAccess.exists(1L)).thenReturn(true);
        when(repository.findByFacilityIdAndStatus(eq(1L), eq(RentalRequestStatus.Converted),
                any(PageRequest.class))).thenReturn(new PageImpl<>(List.of(request)));
        when(bookingReadRepository.findQueueEnrichments(List.of(1L))).thenReturn(Map.of(1L,
                new BookingReadRepository.QueueEnrichment(1L, 10L, "Pending", false, 5L, "A-01",
                        "Rejected", "Không thích khoang này", Instant.now(), 1, 0)));
        when(pricingQuery.intPolicy(ProposalService.POLICY_MAX_REJECTION)).thenReturn(3);

        RentalRequestPageResponse page = service.queue(1L, RentalRequestStatus.Converted, 0, 20);

        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.nextAction()).isEqualTo("REPROPOSE_UNIT");
            assertThat(item.orderId()).isEqualTo(10L);
            assertThat(item.rejectionCount()).isEqualTo(1);
            assertThat(item.maxRejectionCount()).isEqualTo(3);
            assertThat(item.lastProposal().status()).isEqualTo("Rejected");
        });
    }

    @Test
    void queueMarksWaitCustomerWhenProposalPending() {
        RentalRequest request = request(RentalRequestStatus.Converted);
        when(facilityAccess.exists(1L)).thenReturn(true);
        when(repository.findByFacilityIdAndStatus(eq(1L), eq(RentalRequestStatus.Converted),
                any(PageRequest.class))).thenReturn(new PageImpl<>(List.of(request)));
        when(bookingReadRepository.findQueueEnrichments(List.of(1L))).thenReturn(Map.of(1L,
                new BookingReadRepository.QueueEnrichment(1L, 10L, "Pending", false, 5L, "A-01",
                        "Pending", null, Instant.now(), 0, 1)));
        when(pricingQuery.intPolicy(ProposalService.POLICY_MAX_REJECTION)).thenReturn(3);

        RentalRequestPageResponse page = service.queue(1L, RentalRequestStatus.Converted, 0, 20);

        assertThat(page.items().get(0).nextAction()).isEqualTo("WAIT_CUSTOMER");
    }

    @Test
    void queueMarksViewWhenProposalAgreed() {
        RentalRequest request = request(RentalRequestStatus.Converted);
        when(facilityAccess.exists(1L)).thenReturn(true);
        when(repository.findByFacilityIdAndStatus(eq(1L), eq(RentalRequestStatus.Converted),
                any(PageRequest.class))).thenReturn(new PageImpl<>(List.of(request)));
        when(bookingReadRepository.findQueueEnrichments(List.of(1L))).thenReturn(Map.of(1L,
                new BookingReadRepository.QueueEnrichment(1L, 10L, "Pending", false, 5L, "A-01",
                        "Agreed", null, Instant.now(), 0, 0)));
        when(pricingQuery.intPolicy(ProposalService.POLICY_MAX_REJECTION)).thenReturn(3);

        RentalRequestPageResponse page = service.queue(1L, RentalRequestStatus.Converted, 0, 20);

        assertThat(page.items().get(0).nextAction()).isEqualTo("VIEW");
    }

    @Test
    void queueMarksApproveForPendingRequestWithoutOrder() {
        RentalRequest request = request(RentalRequestStatus.Pending);
        when(facilityAccess.exists(1L)).thenReturn(true);
        when(repository.findByFacilityIdAndStatus(eq(1L), eq(RentalRequestStatus.Pending),
                any(PageRequest.class))).thenReturn(new PageImpl<>(List.of(request)));
        when(bookingReadRepository.findQueueEnrichments(List.of(1L))).thenReturn(Map.of());
        when(pricingQuery.intPolicy(ProposalService.POLICY_MAX_REJECTION)).thenReturn(3);

        RentalRequestPageResponse page = service.queue(1L, RentalRequestStatus.Pending, 0, 20);

        assertThat(page.items().get(0).nextAction()).isEqualTo("APPROVE_REQUEST");
    }

    private static RentalRequest request(RentalRequestStatus status) {
        RentalRequest request = mock(RentalRequest.class);
        when(request.getId()).thenReturn(1L);
        when(request.getCustomerName()).thenReturn("Khách");
        when(request.getNormalizedCustomerEmail()).thenReturn("khach@example.com");
        when(request.getCustomerPhone()).thenReturn("0900000000");
        when(request.getUnitTypeId()).thenReturn(1L);
        when(request.getStartDate()).thenReturn(LocalDate.of(2026, 11, 1));
        when(request.getPeriod()).thenReturn(6);
        when(request.getStatus()).thenReturn(status);
        when(request.getCreatedAt()).thenReturn(Instant.now());
        when(request.getExpiresAt()).thenReturn(Instant.now());
        return request;
    }
}
