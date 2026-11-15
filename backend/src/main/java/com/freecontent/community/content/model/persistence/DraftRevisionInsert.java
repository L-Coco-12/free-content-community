package com.freecontent.community.content.model.persistence;

/** 新修订的数据库写入参数，独立于 HTTP 请求与工作区响应。 */
public record DraftRevisionInsert(String id, String contentId, int revisionNumber, String title, String bodyMarkdown) { }
