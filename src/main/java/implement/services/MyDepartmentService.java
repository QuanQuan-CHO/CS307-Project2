package implement.services;

import cn.edu.sustech.cs307.database.SQLDataSource;
import cn.edu.sustech.cs307.dto.Department;
import cn.edu.sustech.cs307.exception.EntityNotFoundException;
import cn.edu.sustech.cs307.service.DepartmentService;
import implement.Util;

import javax.annotation.ParametersAreNonnullByDefault;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@ParametersAreNonnullByDefault
public class MyDepartmentService implements DepartmentService {
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
    public int addDepartment(String name) {
        String sql="insert into department (name) values (?)";
        return Util.addAndGetKey(con,sql,name);
    }

    //TODO:删关联的表
    @Override
    public void removeDepartment(int departmentId) {
        try {//student_section
            String sql1= """
                delete from student_section
                where student_section.student_id in(select student_id from department join major m on department.id = m.department_id
                join student s on m.id = s.major_id
                join student_section on s.id = student_section.student_id
                 where department.id=?);""";
        Util.update(con,sql1,departmentId);
        //student
            String sql2= """
                    delete from student
                    where student.id in(select student.id from department join major m on department.id = m.department_id
                    join student s on m.id = s.major_id
                        where department.id=?)""";
            Util.update(con,sql2,departmentId);
            String sql3= """
                    delete from major_course
                    where major_id in(select m.id from department join major m on department.id = m.department_id
                    join major_course mc on m.id = mc.major_id
                        where department.id=?);""";
            Util.update(con,sql3,departmentId);
            String sql4= """
                    delete from major
                    where major.id in(select m.id from department
                        join major m on department.id = m.department_id
                        where department.id=?);""";
            Util.update(con,sql4,departmentId);
        String sql="delete from department where id=?";
            if(Util.update(con,sql,departmentId)==0){
                throw new EntityNotFoundException();
            }
        } catch (SQLException e) {
            e.printStackTrace();
            System.exit(1);
        }

    }

    //完成√
    @Override
    public List<Department> getAllDepartments() {
        String sql="select * from department";
        return Util.query(Department.class,con,sql);
    }

    //完成√
    @Override
    public Department getDepartment(int departmentId) {
        String sql="select * from department where id=?";
        try {
            return Util.query(Department.class, con, sql, departmentId).get(0);
        }catch (IndexOutOfBoundsException e){
            e.printStackTrace();
            throw new EntityNotFoundException();
        }
    }
}
