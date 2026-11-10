package edu.hust.web.dto;

public record DocumentDto(
    String title,
    String url,
    String snippet,
    float score
) {}