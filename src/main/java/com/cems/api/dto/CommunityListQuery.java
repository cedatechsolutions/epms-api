package com.cems.api.dto;

/**
 * Query parameters for the community list endpoint (spec Module 2 §1: search + municipality/sector
 * filters, paginated). Mutable bean so Spring can bind query params (mirrors {@link UserListQuery}).
 */
public class CommunityListQuery {

    private int page = 1;
    private int perPage = 10;
    private String search;
    private String municipality;
    private String sectorId;
    private String classification;
    private String sort = "name";
    private String direction = "asc";

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

    public String getMunicipality() {
        return municipality;
    }

    public void setMunicipality(String municipality) {
        this.municipality = municipality;
    }

    public String getSectorId() {
        return sectorId;
    }

    public void setSectorId(String sectorId) {
        this.sectorId = sectorId;
    }

    public String getClassification() {
        return classification;
    }

    public void setClassification(String classification) {
        this.classification = classification;
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
