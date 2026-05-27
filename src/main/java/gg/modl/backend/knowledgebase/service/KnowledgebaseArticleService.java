package gg.modl.backend.knowledgebase.service;

import com.github.slugify.Slugify;
import gg.modl.backend.database.mongo.repository.KnowledgebaseArticleMongoRepository;
import gg.modl.backend.knowledgebase.data.KnowledgebaseArticle;
import gg.modl.backend.knowledgebase.dto.request.CreateArticleRequest;
import gg.modl.backend.knowledgebase.dto.request.UpdateArticleRequest;
import gg.modl.backend.server.data.Server;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgebaseArticleService {
    private final KnowledgebaseArticleMongoRepository articleRepository;
    private final Slugify slugify = Slugify.builder().build();
    private static final int MAX_SEARCH_RESULTS = 20;

    public @NotNull List<KnowledgebaseArticle> getArticlesByCategory(@NotNull Server server, @NotNull String categoryId) {
        return articleRepository.findByCategoryOrdered(server, categoryId);
    }

    public @NotNull Map<String, List<KnowledgebaseArticle>> getAllArticlesGroupedByCategory(@NotNull Server server) {
        return articleRepository.findAll(server)
            .stream()
            .collect(Collectors.groupingBy(KnowledgebaseArticle::getCategoryId));
    }

    public @NotNull List<KnowledgebaseArticle> getVisibleArticlesByCategory(@NotNull Server server, @NotNull String categoryId) {
        return articleRepository.findVisibleByCategoryOrdered(server, categoryId);
    }

    public @NotNull Optional<KnowledgebaseArticle> getArticleById(@NotNull Server server, @NotNull String id) {
        return articleRepository.findByArticleId(server, id);
    }

    public @NotNull Optional<KnowledgebaseArticle> getArticleBySlug(@NotNull Server server, @NotNull String slug) {
        return articleRepository.findBySlug(server, slug);
    }

    public @NotNull KnowledgebaseArticle createArticle(@NotNull Server server, @NotNull String categoryId, @NotNull CreateArticleRequest request) {
        KnowledgebaseArticle article = KnowledgebaseArticle.builder()
            .title(request.title())
            .slug(generateUniqueSlug(server, slugify.slugify(request.title()), null))
            .content(request.content())
            .categoryId(categoryId)
            .ordinal(articleRepository.findMaxOrdinalInCategory(server, categoryId) + 1)
            .isVisible(!Boolean.FALSE.equals(request.isVisible()))
            .createdAt(new Date())
            .updatedAt(new Date())
            .build();

        return articleRepository.saveEntity(server, article);
    }

    private String generateUniqueSlug(Server server, String baseSlug, String excludeId) {
        String slug = baseSlug;
        int suffix = 1;

        while (articleRepository.existsBySlug(server, slug, excludeId)) {
            slug = baseSlug + "-" + suffix;
            suffix++;
        }

        return slug;
    }

    public @NotNull Optional<KnowledgebaseArticle> updateArticle(@NotNull Server server, @NotNull String id, @NotNull UpdateArticleRequest request) {
        String uniqueSlug = request.title() != null
                            ? generateUniqueSlug(server, slugify.slugify(request.title()), id)
                            : null;

        return articleRepository.updateArticle(
            server,
            id,
            request.title(),
            uniqueSlug,
            request.content(),
            request.isVisible(),
            new Date()
        );
    }

    public boolean deleteArticle(Server server, String id) {
        return articleRepository.deleteByArticleId(server, id);
    }

    public @NotNull List<KnowledgebaseArticle> searchArticles(@NotNull Server server, @NotNull String searchQuery) {
        return articleRepository.searchVisibleArticles(server, searchQuery, MAX_SEARCH_RESULTS);
    }

    public void reorderArticles(Server server, String categoryId, List<String> ids) {
        articleRepository.reorderArticles(server, categoryId, ids);
    }
}
