package com.example.task.controller;

import com.example.task.dto.BoardDtos;
import com.example.task.service.BoardService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 看板管理 REST 接口。
 */
@RestController
@RequestMapping("/api/boards")
public class BoardController {

    private final BoardService boardService;

    public BoardController(BoardService boardService) {
        this.boardService = boardService;
    }

    @GetMapping
    public List<BoardDtos.BoardItem> list() {
        return boardService.list();
    }

    @PostMapping
    public BoardDtos.BoardItem create(@RequestBody BoardDtos.BoardRequest req) {
        return boardService.create(req);
    }

    @PutMapping("/{id}")
    public BoardDtos.BoardItem update(@PathVariable Long id, @RequestBody BoardDtos.BoardRequest req) {
        return boardService.update(id, req);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        boardService.delete(id);
    }

    @PutMapping("/reorder")
    public void reorder(@RequestBody BoardDtos.BoardReorderRequest req) {
        boardService.reorder(req);
    }
}
