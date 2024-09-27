package io.quarkiverse.antora.test;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

public class SourceMapperTest {

    @Test
    void findLine() {
        Assertions.assertThat(SourceMapper.findLine("0", 0)).isEqualTo(1);
        Assertions.assertThat(SourceMapper.findLine("012", 1)).isEqualTo(1);
        Assertions.assertThat(SourceMapper.findLine("012\n456", 1)).isEqualTo(1);
        Assertions.assertThat(SourceMapper.findLine("012\n456", 4)).isEqualTo(2);
        Assertions.assertThat(SourceMapper.findLine("012\n456", 6)).isEqualTo(2);
    }

}
