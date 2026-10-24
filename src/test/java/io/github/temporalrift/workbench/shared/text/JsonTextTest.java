package io.github.temporalrift.workbench.shared.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class JsonTextTest {

    @Test
    void canonicalSortsObjectKeysRecursivelyAndKeepsArrayOrder() {
        var inner = new LinkedHashMap<String, Object>();
        inner.put("b", 1);
        inner.put("a", List.of(3, 2));
        var outer = new LinkedHashMap<String, Object>();
        outer.put("z", inner);
        outer.put("m", "é");

        assertThat(JsonText.canonical(outer, "x")).isEqualTo("{\"m\":\"é\",\"z\":{\"a\":[3,2],\"b\":1}}");
    }

    @Test
    void unreadableTextNamesWhatWasBeingRead() {
        assertThatThrownBy(() -> JsonText.read("{", Map.class, "The stored thing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The stored thing is not valid JSON");
    }
}
