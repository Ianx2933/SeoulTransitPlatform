package com.ian.transit.curatedod.application;

import com.ian.transit.common.exception.NotFoundException;
import com.ian.transit.curatedod.infrastructure.CuratedOdQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Route names in the representative OD (TOPIS) are not always the names the
 * hourly boarding data and the map use. Compared on 2026-11-24:
 *
 *   boarding / map    representative OD
 *   5522A, 110B       5522A난곡, 110B국민대   the terminus is appended
 *   8333A, 8333B      8333                    A and B are one route in the OD
 *   서대문02          서대문02대, 서대문02소  one route split in two
 *
 * A name typed or linked in the boarding form is matched to the OD name when
 * that gives exactly one route: an exact name first, then the one OD name that
 * is the input followed by Hangul, then the input without a trailing Latin
 * letter. Latin letters match in either case (n75 is N75). When the input matches several OD names the 404 lists them. The
 * route analysis page offers the OD names themselves (GET /api/curated-od/routes),
 * so most visitors never need this.
 */
@Component
@RequiredArgsConstructor
public class RouteNameResolver {

    private static final Pattern TRAILING_LETTER = Pattern.compile("^(.*\\d)[A-Za-z]$");

    /** The names change when the weekly workflow loads a new representative OD. */
    private static final Duration TTL = Duration.ofHours(12);

    private final CuratedOdQueryRepository repository;

    private record Loaded(List<String> names, Instant expires) {
    }

    // Held here rather than with @Cacheable: resolve() calls names() on the same
    // bean, which a Spring cache proxy would not see.
    private volatile Loaded loaded;

    /** Every route name in the representative OD, sorted; read from the database twice a day. */
    public List<String> names() {
        Loaded current = loaded;
        if (current == null || current.expires().isBefore(Instant.now())) {
            current = new Loaded(List.copyOf(repository.findDistinct노선명()), Instant.now().plus(TTL));
            loaded = current;
        }
        return current.names();
    }

    /** The OD name for a route name, or the input itself when nothing matches (the caller's 404). */
    public String resolve(String input) {
        return resolve(input, names());
    }

    static String resolve(String input, Collection<String> names) {
        if (input == null) {
            return null;
        }
        String name = input.trim();
        if (name.isEmpty() || names.contains(name)) {
            return name;
        }
        String sameIgnoringCase = null;
        for (String n : names) {
            if (n.equalsIgnoreCase(name)) {
                if (sameIgnoringCase != null) {
                    sameIgnoringCase = null;
                    break;
                }
                sameIgnoringCase = n;
            }
        }
        if (sameIgnoringCase != null) {
            return sameIgnoringCase;
        }
        List<String> withSuffix = new ArrayList<>();
        for (String n : names) {
            if (n.length() > name.length() && n.regionMatches(true, 0, name, 0, name.length()) && isHangul(n.charAt(name.length()))) {
                withSuffix.add(n);
            }
        }
        if (withSuffix.size() == 1) {
            return withSuffix.get(0);
        }
        if (withSuffix.size() > 1) {
            throw new NotFoundException("No OD for route " + name + "; it is split into "
                    + String.join(", ", withSuffix.stream().sorted().toList()));
        }
        Matcher m = TRAILING_LETTER.matcher(name);
        if (m.matches() && names.contains(m.group(1))) {
            return m.group(1);
        }
        return name;
    }

    private static boolean isHangul(char c) {
        return c >= '가' && c <= '힣';
    }
}
