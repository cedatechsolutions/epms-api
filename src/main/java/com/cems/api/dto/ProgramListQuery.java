package com.cems.api.dto;

/**
 * Query parameters for the program list endpoint (spec Module 5 §1: status tabs plus community/
 * type/search filters, paginated). Mutable bean so Spring can bind query params (mirrors
 * {@link CommunityListQuery}).
 *
 * <p>{@code status} accepts either a single status or the pseudo-status {@code under_review}, which
 * expands to the three in-chain statuses behind that tab.
 */
public class ProgramListQuery {

    /** Pseudo-status matching the spec's "Under Review" tab. */
    public static final String STATUS_UNDER_REVIEW = "under_review";

    private int page = 1;
    private int perPage = 10;
    private String search;
    private String status;
    private String communityId;
    private String programTypeId;
    private String facultyLeadId;
    /**
     * Academic period to scope the list to (spec Module 6 AC 6). This is what makes every dashboard
     * KPI clickable: the dashboard and this list apply the same rule — proposed date inside the
     * period — so the count on the card equals the row count on the screen it opens.
     */
    private String periodId;
    private String sort = "createdAt";
    private String direction = "desc";

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getPerPage() {
        return perPage;
    }

    public void setPerPage(int perPage) {
        this.perPage = perPage;
    }

    public String getSearch() {
        return search;
    }

    public void setSearch(String search) {
        this.search = search;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCommunityId() {
        return communityId;
    }

    public void setCommunityId(String communityId) {
        this.communityId = communityId;
    }

    public String getProgramTypeId() {
        return programTypeId;
    }

    public void setProgramTypeId(String programTypeId) {
        this.programTypeId = programTypeId;
    }

    public String getFacultyLeadId() {
        return facultyLeadId;
    }

    public void setFacultyLeadId(String facultyLeadId) {
        this.facultyLeadId = facultyLeadId;
    }

    public String getPeriodId() {
        return periodId;
    }

    public void setPeriodId(String periodId) {
        this.periodId = periodId;
    }

    public String getSort() {
        return sort;
    }

    public void setSort(String sort) {
        this.sort = sort;
    }

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }
}
