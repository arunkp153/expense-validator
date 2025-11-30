package com.smartexpense.expensevalidator.model;

public class CategoryRule {
    private final String keyword;
    private final String category;

    public CategoryRule(String keyword, String category) {
        this.keyword = keyword;
        this.category = category;
    }

    public String getKeyword() { return keyword; }
    public String getCategory() { return category; }
}
