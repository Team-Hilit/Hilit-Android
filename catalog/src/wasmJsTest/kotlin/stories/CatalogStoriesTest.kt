package stories

import util.findFirstStoryLeaf
import kotlin.test.Test
import kotlin.test.assertNotNull

class CatalogStoriesTest {
    @Test
    fun `등록된 모든 Story로 카탈로그 탐색 트리를 생성한다`() {
        assertNotNull(CatalogStoryTree.findFirstStoryLeaf())
    }
}
