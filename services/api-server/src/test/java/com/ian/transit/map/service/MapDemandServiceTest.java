package com.ian.transit.map.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ian.transit.map.repository.DistrictDemandRepository;
import com.ian.transit.map.repository.MapDemandRepository;
import com.ian.transit.map.repository.NodeCatchmentRepository;
import com.ian.transit.map.repository.NodeDemandRepository;
import com.ian.transit.map.repository.NodeSearchRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the public request contract owned by {@link MapDemandService}.
 * Private parsers are exercised through public use cases rather than reflection.
 */
class MapDemandServiceTest {

    private MapDemandRepository mapDemandRepository;
    private DistrictDemandRepository districtDemandRepository;
    private NodeDemandRepository nodeDemandRepository;
    private NodeCatchmentRepository nodeCatchmentRepository;
    private NodeSearchRepository nodeSearchRepository;
    private MapDemandService service;

    @BeforeEach
    void setUp() {
        mapDemandRepository = mock(MapDemandRepository.class);
        districtDemandRepository = mock(DistrictDemandRepository.class);
        nodeDemandRepository = mock(NodeDemandRepository.class);
        nodeCatchmentRepository = mock(NodeCatchmentRepository.class);
        nodeSearchRepository = mock(NodeSearchRepository.class);
        service = new MapDemandService(
                mapDemandRepository,
                districtDemandRepository,
                nodeDemandRepository,
                nodeCatchmentRepository,
                nodeSearchRepository
        );
    }

    @Test
    void mapDemandRejectsUnsupportedModeBeforeQueryingRepository() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.getMapDemand(
                        "tram", "mon", null, "average", 8, null, null, null
                )
        );

        assertEquals("mode must be either 'bus' or 'subway'", error.getMessage());
        verifyNoInteractions(mapDemandRepository);
    }

    @Test
    void mapDemandRejectsSundayAliasBecauseStoredBucketIsSunHoliday() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.getMapDemand(
                        "bus", "sun", null, "average", 8, null, null, null
                )
        );

        assertTrue(error.getMessage().contains("sun_holiday"));
        verifyNoInteractions(mapDemandRepository);
    }

    @Test
    void mapDemandRejectsHourOutsideZeroToTwentyThree() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.getMapDemand(
                        "bus", "mon", null, "average", 24, null, null, null
                )
        );

        assertEquals("hour must be between 0 and 23", error.getMessage());
        verifyNoInteractions(mapDemandRepository);
    }

    @Test
    void mapDemandParsesAndDeduplicatesMultiValueInputs() {
        service.getMapDemand(
                "bus",
                null,
                "mon, tue, mon",
                "sum",
                null,
                null,
                "7, 8, 8",
                "R1, R2"
        );

        verify(mapDemandRepository).findMapDemand(
                "bus",
                List.of("mon", "tue"),
                "sum",
                List.of(7, 8),
                List.of("R1", "R2")
        );
    }

    @Test
    void nodeSearchRejectsBlankAndSingleCharacterKeywords() {
        IllegalArgumentException blank = assertThrows(
                IllegalArgumentException.class,
                () -> service.searchNodes("   ", 30)
        );
        IllegalArgumentException oneCharacter = assertThrows(
                IllegalArgumentException.class,
                () -> service.searchNodes("강", 30)
        );

        assertTrue(blank.getMessage().contains("must not be blank"));
        assertTrue(oneCharacter.getMessage().contains("at least 2 characters"));
        verifyNoInteractions(nodeSearchRepository);
    }

    @Test
    void nodeSearchNormalizesKeywordAndCapsLimit() {
        service.searchNodes("  강남역  ", 500);

        verify(nodeSearchRepository).searchNodes("강남역", 100);
    }

    @Test
    void nodeCatchmentRejectsCoordinateOutsideServiceBounds() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.getNodeCatchmentDemand(
                        40.0,
                        127.0,
                        800,
                        "bus",
                        "mon",
                        null,
                        "average",
                        8,
                        null
                )
        );

        assertEquals("coordinate is outside supported bounds", error.getMessage());
        verifyNoInteractions(nodeCatchmentRepository);
    }

    @Test
    void nodeCatchmentRejectsUnsupportedRadius() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.getNodeCatchmentDemand(
                        37.5,
                        127.0,
                        500,
                        "bus",
                        "mon",
                        null,
                        "average",
                        8,
                        null
                )
        );

        assertEquals("radiusMeters must be one of 400, 800, or 1000", error.getMessage());
        verifyNoInteractions(nodeCatchmentRepository);
    }

    @Test
    void districtDemandRejectsNodeLimitOutsideContract() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.getDistrictDemand(
                        "11010530",
                        null,
                        "bus",
                        "mon",
                        null,
                        "average",
                        8,
                        null,
                        10001
                )
        );

        assertEquals("nodeLimit must be between 1 and 10000", error.getMessage());
        verifyNoInteractions(districtDemandRepository);
    }

    @Test
    void districtDemandRejectsMalformedDistrictCode() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.getDistrictDemand(
                        "ABC",
                        null,
                        "bus",
                        "mon",
                        null,
                        "average",
                        8,
                        null,
                        null
                )
        );

        assertEquals("districtCode must contain 8 to 10 digits", error.getMessage());
        verifyNoInteractions(districtDemandRepository);
    }
}
