package com.cems.api.dto;

/**
 * Query parameters for the survey list endpoint (spec Module 3 §1: search + status filter,
 * paginated). Mutable bean so Spring can bind query params (mirrors {@link CommunityListQuery}).
 */
public class SurveyListQuery {

    private int page = 1;
    private int perPage = 10;
    private String search;
    private String status;
    private String communityId;
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
