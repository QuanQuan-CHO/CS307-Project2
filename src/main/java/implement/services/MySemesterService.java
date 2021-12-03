package implement.services;

import cn.edu.sustech.cs307.database.SQLDataSource;
import cn.edu.sustech.cs307.dto.Department;
import cn.edu.sustech.cs307.dto.Semester;
import cn.edu.sustech.cs307.dto.prerequisite.AndPrerequisite;
import cn.edu.sustech.cs307.dto.prerequisite.Prerequisite;
import cn.edu.sustech.cs307.exception.EntityNotFoundException;
import cn.edu.sustech.cs307.exception.IntegrityViolationException;
import cn.edu.sustech.cs307.service.SemesterService;

import javax.annotation.ParametersAreNonnullByDefault;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

@ParametersAreNonnullByDefault
public class MySemesterService implements SemesterService {
    @Override
    public int addSemester(String name, Date begin, Date end) {
        try(Connection con = SQLDataSource.getInstance().getSQLConnection()){
            String sql="insert into semester(name, begin_time, end_time) values (?,?,?)";
            PreparedStatement ps = con.prepareStatement(sql);
                ps.setString(1, name);
                ps.setDate(2, begin);
                ps.setDate(3, end);
            ps.executeUpdate();
            return ps.getGeneratedKeys().getInt(1);
        }catch(SQLException throwables){
            throwables.printStackTrace();
            throw new IntegrityViolationException();
        }
    }

    @Override
    public void removeSemester(int semesterId) {
        try(Connection con = SQLDataSource.getInstance().getSQLConnection()){
            String sql="delete from department where id = ?";
            PreparedStatement ps = con.prepareStatement(sql);
                ps.setInt(1, semesterId);
        }catch(SQLException throwables){
            throwables.printStackTrace();
        }
    }

    @Override
    public List<Semester> getAllSemesters() {
        ArrayList<Semester> semesters = new ArrayList<>();
        try(Connection con=SQLDataSource.getInstance().getSQLConnection()) {
            String sql="select * from semester";
            PreparedStatement ps = con.prepareStatement(sql);
            ResultSet rs = ps.executeQuery();
            while (rs.next()){
                int id = rs.getInt(1);
                String name = rs.getString(2);
                Date begin = rs.getDate(3);
                Date end = rs.getDate(4);
                semesters.add(new Semester(id, name, begin, end));
            }
            ps.close();
        } catch (Exception throwables) {
            throwables.printStackTrace();
        }
        return semesters;
    }

    @Override
    public Semester getSemester(int semesterId) {
        try(Connection con=SQLDataSource.getInstance().getSQLConnection()) {
            String sql="select * from semester where id = ?";
            PreparedStatement ps = con.prepareStatement(sql);
            ps.setInt(1,semesterId);
            ResultSet rs = ps.executeQuery();
            int id = rs.getInt(1);
            String name = rs.getString(2);
            Date begin = rs.getDate(3);
            Date end = rs.getDate(4);
            Semester semester = new Semester(id, name, begin, end);
            ps.close();
            return semester;

        } catch (SQLException throwables) {
            throwables.printStackTrace();
            throw new EntityNotFoundException();
        }
    }
}
