// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Limited.

package com.starrocks.sql.plan;

import com.starrocks.utframe.UtFrameUtils;
import org.junit.Assert;
import org.junit.Test;

public class DigestTest extends PlanTestBase {

    @Test
    public void testWhere() throws Exception {
        String sql1 = "select s_address from supplier where a > 1";
        String sql2 = "select s_address from supplier where a > 5";
        String digest1 = UtFrameUtils.getStmtDigest(connectContext, sql1);
        String digest2 = UtFrameUtils.getStmtDigest(connectContext, sql2);
        Assert.assertEquals(digest1, digest2);

        sql1 = "select s_address from supplier where a like 'xxx' ";
        sql2 = "select s_address from supplier where a like 'kkskkkkkkkkk' ";
        digest1 = UtFrameUtils.getStmtDigest(connectContext, sql1);
        digest2 = UtFrameUtils.getStmtDigest(connectContext, sql2);
        Assert.assertEquals(digest1, digest2);

        sql1 = "select s_address from supplier where a < 2 and b > -1 ";
        sql2 = "select s_address from supplier where a < 1000      and b > 100000 ";
        digest1 = UtFrameUtils.getStmtDigest(connectContext, sql1);
        digest2 = UtFrameUtils.getStmtDigest(connectContext, sql2);
        Assert.assertEquals(digest1, digest2);

        sql1 = "select s_address from supplier where a < 2 or b > -1 ";
        sql2 = "select s_address from supplier where a < 3 or  b > 100000 ";
        digest1 = UtFrameUtils.getStmtDigest(connectContext, sql1);
        digest2 = UtFrameUtils.getStmtDigest(connectContext, sql2);
        Assert.assertEquals(digest1, digest2);

        sql1 = "select s_address from supplier where not a < 2  ";
        sql2 = "select s_address from supplier where not a < 3";
        digest1 = UtFrameUtils.getStmtDigest(connectContext, sql1);
        digest2 = UtFrameUtils.getStmtDigest(connectContext, sql2);
        Assert.assertEquals(digest1, digest2);

        sql1 = "select s_address from supplier where not a < 2  ";
        sql2 = "select s_address from supplier where not a > 3";
        digest1 = UtFrameUtils.getStmtDigest(connectContext, sql1);
        digest2 = UtFrameUtils.getStmtDigest(connectContext, sql2);
        Assert.assertNotEquals(digest1, digest2);
    }

    @Test
    public void testLimit() throws Exception {
        String sql1 = "select s_address from supplier where a > 1 limit 1";
        String sql2 = "select s_address from supplier where a > 5 limit 20";
        String digest1 = UtFrameUtils.getStmtDigest(connectContext, sql1);
        String digest2 = UtFrameUtils.getStmtDigest(connectContext, sql2);
        Assert.assertEquals(digest1, digest2);

        sql1 = "select s_address from supplier where a > 1 order by a limit 1";
        sql2 = "select s_address from supplier where a > 5 order by a limit 20";
        digest1 = UtFrameUtils.getStmtDigest(connectContext, sql1);
        digest2 = UtFrameUtils.getStmtDigest(connectContext, sql2);
        Assert.assertEquals(digest1, digest2);
    }

    @Test
    public void testFunction() throws Exception {
        String sql1 = "select substr(s_address, 1, 2) from supplier where a > 1 limit 1";
        String sql2 = "select substr(s_address, 1, 5) from supplier where a > 1 limit 1";
        String digest1 = UtFrameUtils.getStmtDigest(connectContext, sql1);
        String digest2 = UtFrameUtils.getStmtDigest(connectContext, sql2);
        Assert.assertEquals(digest1, digest2);
    }

    @Test
    public void testArithmetic() throws Exception {
        String sql1 = "select a + 1 from supplier";
        String sql2 = "select a + 2 from supplier";
        String digest1 = UtFrameUtils.getStmtDigest(connectContext, sql1);
        String digest2 = UtFrameUtils.getStmtDigest(connectContext, sql2);
        Assert.assertEquals(digest1, digest2);
    }

    @Test
    public void testCaseWhen() throws Exception {
        String sql1 = "select v1+20, case v2 when v3 then 1 else 0 end from t0 where v1 is null";
        String sql2 = "select v1+20, case v2 when v3 then 1000 else 9999999 end from t0 where v1 is null";
        String digest1 = UtFrameUtils.getStmtDigest(connectContext, sql1);
        String digest2 = UtFrameUtils.getStmtDigest(connectContext, sql2);
        Assert.assertEquals(digest1, digest2);
    }

    @Test
    public void testSubquery() throws Exception {
        String sql1 = "select sum(l_extendedprice) / 7.0 as avg_yearly from lineitem, part where p_partkey = " +
                "l_partkey and p_brand = 'Brand#23' and p_container = 'MED BOX' " +
                "and l_quantity < ( select 0.2 * avg(l_quantity) from lineitem where l_partkey = p_partkey );";
        String sql2 = "select sum(l_extendedprice) / 7.0 as avg_yearly from lineitem, part where p_partkey = " +
                "l_partkey and p_brand = 'Brand#23' and p_container = 'MED BOX' " +
                "and l_quantity < ( select 1 * avg(l_quantity) from lineitem where l_partkey = p_partkey );";
        String digest1 = UtFrameUtils.getStmtDigest(connectContext, sql1);
        String digest2 = UtFrameUtils.getStmtDigest(connectContext, sql2);
        Assert.assertEquals(digest1, digest2);
    }
}
