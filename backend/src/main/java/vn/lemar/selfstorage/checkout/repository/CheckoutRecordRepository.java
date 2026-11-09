package vn.lemar.selfstorage.checkout.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.lemar.selfstorage.checkout.domain.CheckoutRecord;

public interface CheckoutRecordRepository extends JpaRepository<CheckoutRecord, Long> {

    Optional<CheckoutRecord> findByReturnRequestId(Long returnRequestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CheckoutRecord c where c.returnRequestId = :returnRequestId")
    Optional<CheckoutRecord> findByReturnRequestIdForUpdate(@Param("returnRequestId") Long returnRequestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CheckoutRecord c where c.shortfallInvoiceId = :invoiceId")
    Optional<CheckoutRecord> findByShortfallInvoiceIdForUpdate(@Param("invoiceId") Long invoiceId);
}
