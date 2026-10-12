package com.abc.animeservice.datasource;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Registry that holds all configured AnimeDataSources.
 * animeko uses SPI to auto-discover them; we pre-register them explicitly.
 */
@Getter
@RequiredArgsConstructor
@org.springframework.stereotype.Component
public class DataSourceRegistry {

    private final BangumiDataSource bangumiDataSource;

    private final Css1DataSource css1DataSource;

    /** Get the single metadata source — could be extended to a List<AnimeDataSource> in the future */
    public AnimeDataSource getMetadataSource() {
        return bangumiDataSource;
    }

    /** Get the video source — currently css1.json based */
    public Css1DataSource getVideoSource() {
        return css1DataSource;
    }
}
