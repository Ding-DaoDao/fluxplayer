package com.github.eprendre.test_source;

import com.github.eprendre.tingshu.sources.AudioUrlCustomExtractor;
import com.github.eprendre.tingshu.sources.AudioUrlExtraHeaders;
import com.github.eprendre.tingshu.sources.AudioUrlExtractor;
import com.github.eprendre.tingshu.sources.ConfigurableSource;
import com.github.eprendre.tingshu.sources.TingShu;
import com.github.eprendre.tingshu.utils.Book;
import com.github.eprendre.tingshu.utils.BookDetail;
import com.github.eprendre.tingshu.utils.Category;
import com.github.eprendre.tingshu.utils.CategoryMenu;
import com.github.eprendre.tingshu.utils.CategoryTab;
import com.github.eprendre.tingshu.utils.ConfigItem;
import com.github.eprendre.tingshu.utils.Episode;
import com.github.eprendre.tingshu.utils.ExternalSourcePrefs;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import kotlin.Pair;

// 真实动态加载的测试书源，仅打入测试 APK，不随正式应用发布。
public final class SourceEntry {
    public static List<TingShu> getSources() {
        return Collections.singletonList(new TestSource());
    }

    public static final class TestSource extends TingShu implements ConfigurableSource, AudioUrlExtraHeaders {
        @Override public String getSourceId() { return "flux-listening-test"; }
        @Override public String getUrl() { return "https://example.com"; }
        @Override public String getName() { return "听书集成测试源"; }
        @Override public boolean isWebViewNotRequired() { return true; }

        @Override public List<CategoryMenu> getCategoryMenus() {
            return Collections.singletonList(new CategoryMenu("分类", Collections.singletonList(new CategoryTab("测试书籍", "catalog"))));
        }

        private Book testBook() {
            return new Book("", "book-one", "测试书籍", "测试作者", "测试演播");
        }

        @Override public Pair<List<Book>, Integer> search(String keywords, int page) {
            return new Pair<>(Collections.singletonList(testBook()), 1);
        }

        @Override public Category getCategoryList(String url) {
            return new Category(Collections.singletonList(testBook()), 1, 1, url, "");
        }

        @Override public BookDetail getBookDetailInfo(String url, boolean loadEpisodes, boolean loadFullPages) {
            return new BookDetail(Arrays.asList(new Episode("第一章", "one"), new Episode("第二章", "two")), "集成测试简介", "", "", 2, "");
        }

        @Override public AudioUrlExtractor getAudioUrlExtractor() {
            AudioUrlCustomExtractor.INSTANCE.setUp(chapter -> ExternalSourcePrefs.INSTANCE.getString("flux-listening-test.endpoint", "http://127.0.0.1") + "/" + chapter + ".wav");
            return AudioUrlCustomExtractor.INSTANCE;
        }

        @Override public List<ConfigItem> getCustomConfigItems() {
            return Collections.singletonList(new ConfigItem.Text("endpoint", "测试服务地址", "http://127.0.0.1"));
        }

        @Override public Map<String, String> headers(String url) {
            return url.startsWith("http://127.0.0.1:") ? Collections.singletonMap("X-Flux-Listening-Test", "fixture") : Collections.emptyMap();
        }
    }
}
