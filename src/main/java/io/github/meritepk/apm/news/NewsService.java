package io.github.meritepk.apm.news;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class NewsService {

    private final NewsRepository repository;

    public NewsService(NewsRepository repository) {
        this.repository = repository;
    }

    public Page<News> findAll(int page, int size) {
        return repository.findByOrderByIdDesc(PageRequest.of(page, size));
    }
}
