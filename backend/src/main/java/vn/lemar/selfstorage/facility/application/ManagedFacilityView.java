package vn.lemar.selfstorage.facility.application;

/** Cơ sở mà FM hiện tại phụ trách — {@code facilities.fm_account_id} là quan hệ 1–1. */
public record ManagedFacilityView(Long id, String code, String name, String address, String operatingHours,
                                  String status) {
}
