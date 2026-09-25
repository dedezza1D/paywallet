package br.com.paywallet.feed;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import br.com.paywallet.feed.FeedService.FeedItem;
import br.com.paywallet.feed.FeedService.PublicFeedItem;

@RestController
public class FeedController {

    private final FeedService service;

    public FeedController(FeedService service) {
        this.service = service;
    }

    @GetMapping("/feed")
    public Page<PublicFeedItem> publicFeed(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return service.publicFeed(pageable);
    }

    @GetMapping("/users/{id}/feed")
    public Page<FeedItem> userFeed(
            @PathVariable Long id,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return service.userFeed(id, pageable);
    }
}
