import com.github.xingzheli.bilidetox.hook.SearchResultGate;
import java.util.*;

public class SearchResultGateTest {
    static List<SearchResultGate.Entry<String>> videos(int start, int count, int matched) {
        var entries = new ArrayList<SearchResultGate.Entry<String>>();
        for (int i = start; i < start + count; i++) entries.add(new SearchResultGate.Entry<>("v" + i, true, i == matched));
        return entries;
    }
    static void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
    public static void main(String[] args) {
        var blocked = new SearchResultGate<String>();
        check(blocked.accept(1, videos(1, 20, 11)).getStatus() == SearchResultGate.Status.BLOCKED, "eleventh hit cannot validate");
        check(blocked.accept(2, videos(21, 20, 21)).getItems().isEmpty(), "later page cannot reopen blocked search");
        var allow = new SearchResultGate<String>();
        var first = allow.accept(1, videos(1, 20, 10));
        check(first.getStatus() == SearchResultGate.Status.ALLOWED, "tenth hit validates");
        check(first.getItems().equals(List.of("v1","v2","v3","v4","v5","v10")), "exempt only five, not ten");
        check(allow.accept(2, videos(21, 20, 21)).getItems().equals(List.of("v21")), "pagination gets no new exemptions");
        check(allow.accept(1, videos(1, 20, 10)).equals(first), "retry does not consume exemptions");
        var shortPage = new SearchResultGate<String>();
        check(shortPage.accept(1, videos(1, 7, -1)).getStatus() == SearchResultGate.Status.BLOCKED, "short first page blocks immediately");
        check(shortPage.accept(2, videos(8, 7, 8)).getItems().isEmpty(), "next page cannot complete validation");
        var shortValid = new SearchResultGate<String>();
        check(shortValid.accept(1, videos(1, 7, 7)).getItems().equals(List.of("v1","v2","v3","v4","v5","v7")), "short first page with match retains only five exemptions");
        check(new SearchResultGate<String>().accept(1, videos(1, 3, -1)).getStatus() == SearchResultGate.Status.BLOCKED, "three unmatched videos block");
        var cards = new ArrayList<>(videos(1, 10, 10));
        cards.add(0, new SearchResultGate.Entry<>("author", false, false));
        check(new SearchResultGate<String>().accept(1, cards).getItems().equals(List.of("author","v1","v2","v3","v4","v5","v10")), "nonvideo cards do not consume either window");
        var tiny = new SearchResultGate<String>();
        check(tiny.accept(1, videos(1, 2, 2)).getItems().size() == 2, "tiny valid first page");
        check(tiny.accept(2, videos(3, 5, -1)).getItems().equals(List.of("v3","v4","v5")), "five exemptions total across short pages");
        check(new SearchResultGate<String>().accept(2, videos(1, 20, 1)).getStatus() == SearchResultGate.Status.BLOCKED, "cannot validate from page two");
        System.out.println("SearchResultGate: 14 first-page validation and exemption checks passed");
    }
}
