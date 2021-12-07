package implement;

import cn.edu.sustech.cs307.exception.IntegrityViolationException;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.sql.*;
import java.time.DayOfWeek;
import java.util.ArrayList;

public class Util {
    /**
     * 通用的增、删、改操作，返回受影响的行数
     */
    public static int update(Connection con,String sql,Object... param){
        try {
            PreparedStatement ps = con.prepareStatement(sql);
            for (int i = 0; i < param.length; i++) {
                ps.setObject(i + 1, param[i]);
            }
            return ps.executeUpdate();
        } catch (SQLException e){
            e.printStackTrace();
            System.exit(1);
            return -1;
        }
    }

    /**
     * 通用的add操作，返回自动生成的主键
     */
    public static int addAndGetKey(Connection con,String sql,Object... param){
        try (PreparedStatement ps=con.prepareStatement(sql,PreparedStatement.RETURN_GENERATED_KEYS)){
            for (int i = 0; i < param.length; i++) {
                ps.setObject(i+1,param[i]);
            }
            try {
                ps.executeUpdate();
            }catch (SQLException e){
                e.printStackTrace();
                throw new IntegrityViolationException();
            }
            ResultSet rs = ps.getGeneratedKeys();
            rs.next();
            return ps.getGeneratedKeys().getInt("id");
        } catch (SQLException e) {
            e.printStackTrace();
            System.exit(1);
            return -1;
        }
    }

    /**
     * 通用的查询操作，注意：**列的别名必须与属性名一致**
     */
    public static <T> ArrayList<T> query(Class<T> clazz,Connection con,String sql,Object... param){
        try(PreparedStatement ps=con.prepareStatement(sql)){
            for (int i = 0; i < param.length; i++) {
                ps.setObject(i+1,param[i]);
            }
            ResultSet rs;
            rs = ps.executeQuery();
            ResultSetMetaData rsmd = rs.getMetaData();
            int col = rsmd.getColumnCount();
            Constructor<T> defConstr = clazz.getConstructor();
            ArrayList<T> list = new ArrayList<>();
            while(rs.next()){
                T t = defConstr.newInstance();
                for (int i = 0; i < col; i++) {
                    Object val;
                    if(rsmd.getColumnName(i+1).equals("day_of_week")) {
                        val=DayOfWeek.of(rs.getInt(i+1));
                    }else {
                        val = rs.getObject(i+1);
                    }
                    String fieldName = rsmd.getColumnLabel(i+1);
                    Field field = clazz.getDeclaredField(fieldName);
                    field.set(t,val);
                }
                list.add(t);
            }
            rs.close();
            return list;
        } catch (NoSuchFieldException|NoSuchMethodException|InstantiationException|IllegalAccessException|InvocationTargetException|SQLException e) {
            e.printStackTrace();
            System.exit(1);
            return null;
        }
    }
}
