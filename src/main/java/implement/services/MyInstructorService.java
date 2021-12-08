package implement.services;

import cn.edu.sustech.cs307.database.SQLDataSource;
import cn.edu.sustech.cs307.dto.CourseSection;
import cn.edu.sustech.cs307.exception.EntityNotFoundException;
import cn.edu.sustech.cs307.exception.IntegrityViolationException;
import cn.edu.sustech.cs307.service.InstructorService;
import implement.Util;

import javax.annotation.ParametersAreNonnullByDefault;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@ParametersAreNonnullByDefault
public class MyInstructorService implements InstructorService {
    Connection con;
    {
        try {
            con = SQLDataSource.getInstance().getSQLConnection();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    //完成√
    @Override
    public void addInstructor(int userId, String firstName, String lastName) {
        try{
            String sql="insert into instructor (id,first_name,last_name) values (?,?,?)";
            Util.update(con,sql,userId,firstName,lastName);
        } catch (SQLException e) {
            e.printStackTrace();
            throw new IntegrityViolationException();
        }
    }

    //完成√
    @Override
    public List<CourseSection> getInstructedCourseSections(int instructorId, int semesterId) {
        String sql = """
                select i.id,
                       sec.name,
                       left_capacity "leftCpacity",
                       total_capacity "totalCapacity"
                from instructor i
                     join section_class sc on i.id = sc.instructor_id
                                          and i.id=?
                     join section sec on sc.section_id=sec.id
                     join semester sem on sec.semester_id = sem.id
                                      and sec.id=?;""";
        //这里的queryRes有重复的CourseSection，因为join了section_class
        ArrayList<CourseSection> queryRes = Util.query(CourseSection.class, con, sql, instructorId, semesterId);
        if (queryRes.isEmpty()) {
            throw new EntityNotFoundException();
        }
        return queryRes.stream().distinct().collect(Collectors.toList());
    }
}