package com.quanquan.service.impl;

import com.quanquan.dto.*;
import com.quanquan.dto.grade.Grade;
import com.quanquan.dto.grade.HundredMarkGrade;
import com.quanquan.dto.grade.PassOrFailGrade;
import com.quanquan.exception.EntityNotFoundException;
import com.quanquan.exception.IntegrityViolationException;
import com.quanquan.service.StudentService;
import com.quanquan.service.impl.assist.ClassInfo;
import com.quanquan.service.impl.assist.Info;
import com.quanquan.service.impl.assist.SelectedInfo;
import com.quanquan.util.Util;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import javax.sql.DataSource;
import java.sql.Date;
import java.sql.*;
import java.time.DayOfWeek;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@ParametersAreNonnullByDefault
@Service
public class MyStudentService implements StudentService {

    @Autowired
    DataSource dataSource;

    @Override
    public String getPasswordById(int id) {
        try(Connection con=dataSource.getConnection()){
            String sql="select password from mybatis.student where id=?";
            ArrayList<String> res = Util.querySingle(con, sql, id);
            return res.isEmpty()?"":res.get(0);
        }catch (SQLException e){
            e.printStackTrace();
            return "";
        }
    }

    @Override
    public void addStudent(int userId, int majorId, String firstName, String lastName, Date enrolledDate,String password) {
        try(Connection con=dataSource.getConnection()) {
            String fullName;
            if(firstName.charAt(0) >= 'A' && firstName.charAt(0) <= 'Z') fullName = firstName + " " + lastName;
            else fullName = firstName + lastName;
            String sql="insert into mybatis.student (id,major_id,full_name,enrolled_date,password) values (?,?,?,?,?)";
            Util.update(con, sql, userId, majorId, fullName, enrolledDate, password);
        }catch (SQLException e) {
            e.printStackTrace();
            throw new IntegrityViolationException();
        }
    }

    /**
     * CourseSearchEntry?????????Section
     */
    @Override
    public List<CourseSearchEntry> searchCourse(int studentId, int semesterId, @Nullable String searchCid, @Nullable String searchName, @Nullable String searchInstructor, @Nullable DayOfWeek searchDayOfWeek, @Nullable Short searchClassTime, @Nullable List<String> searchClassLocations, CourseType searchCourseType, boolean ignoreFull, boolean ignoreConflict, boolean ignorePassed, boolean ignoreMissingPrerequisites, int pageSize, int pageIndex) {
        try (Connection con=dataSource.getConnection()){
            //0.??????????????????: infos
            String sql= """
                    select left_capacity "leftCapacity",
                           course_id "courseId",
                           c.name||'['||sec.name||']' courseFullName,
                           full_name "instructorFullName",
                           day_of_week "dayOfWeek",
                           class_begin "classBegin",class_end "classEnd",
                           location,
                           sec.id "sectionId",
                           instructor_id "instructorId",
                           sc.id "classId",
                           week_list "weekList"
                    from mybatis.section sec
                            join mybatis.semester sem on sec.semester_id=sem.id
                                           and sem.id=?
                            join mybatis.section_class sc on sec.id = sc.section_id
                            join mybatis.instructor i on sc.instructor_id = i.id
                            join mybatis.course c on sec.course_id = c.id
                    order by "courseId","courseFullName";""";
            Stream<Info> infos = Util.query(Info.class, con, sql,semesterId).stream().parallel();
            //1.??????searchCid & searchName & ignoreFull(??????section??????)
            if(searchCid!=null && !searchCid.equals("")){
                infos=infos.filter(info -> info.courseId.contains(searchCid));
            }
            if(searchName!=null && !searchName.equals("")){
                infos=infos.filter(info -> info.courseFullName.contains(searchName));
            }
            if(ignoreFull){
                infos=infos.filter(info -> info.leftCapacity>0);
            }
            List<Info> infoList = infos.collect(Collectors.toList());//Stream???List
            //2.??????4?????????????????????(????????????class?????????????????????)
            HashSet<Integer> filteredSids = new HashSet<>();
            for (Info info : infoList) {
                if((searchInstructor==null||searchInstructor.equals("")||info.instructorFullName.replace(" ","").contains(searchInstructor.replace(" ","")))&&
                        (searchDayOfWeek==null||info.dayOfWeek == searchDayOfWeek)&&
                        (searchClassTime==null||info.classBegin <= searchClassTime && info.classEnd >= searchClassTime)
                        ){
                    if(searchClassLocations!=null && !searchClassLocations.isEmpty()){
                        for (String eachLocation : searchClassLocations) {
                            if(info.location.contains(eachLocation)){
                                filteredSids.add(info.sectionId);
                                break;
                            }
                        }
                    }else{filteredSids.add(info.sectionId);}
                }
            }
            infoList.removeIf(info -> !filteredSids.contains(info.sectionId));
            //----------CourseType??????????????????-------------
            if(searchClassLocations!=null && (searchClassLocations.isEmpty()||searchClassLocations.get(0).equals(""))){
                infoList=new ArrayList<>();
            }
            //3.??????ignorePassed & ignoreMissingPrerequisites
            if(ignorePassed || ignoreMissingPrerequisites){
                //3.1.0?????????????????????pass?????????courseId: passedCids
                sql= """
                        select distinct course_id
                        from mybatis.student_section
                             join mybatis.section on section_id = id
                                         and student_id=?
                                         and (mark>=60 or mark=-2)""";
                ArrayList<String> passedCids= Util.querySingle(con,sql,studentId);
                //3.1.1??????ignorePassed
                if(ignorePassed){
                    infoList.removeIf(info -> passedCids.contains(info.courseId));
                }
                //3.1.2??????ignoreMissingPrerequisites
                if(ignoreMissingPrerequisites){
                    //Motivation: ????????????infos????????????courseId???????????????????????????
                    //3.1.2.1????????????????????????courseId???HashSet: cids
                    HashSet<String> cids = new HashSet<>();
                    for (Info info : infoList) {
                        cids.add(info.courseId);
                    }
                    //3.1.2.2????????????????????????cids?????????filCids
                    ArrayList<String> filCids = (ArrayList<String>) cids.stream().
                                                filter(cid -> passedPre(passedCids, cid)).
                                                collect(Collectors.toList());
                    //3.1.2.3??????filCids??????infos
                    infoList.removeIf(info -> !filCids.contains(info.courseId));
                }
            }
            //4.????????????CourseSearchEntry: entries
            ArrayList<CourseSearchEntry> entries = new ArrayList<>();
            //4.1?????????????????????sectionIds
            ArrayList<Integer> sectionIds = new ArrayList<>();
            for (Info info : infoList) {
                if(!sectionIds.contains(info.sectionId)){
                    sectionIds.add(info.sectionId);
                }
            }
            //4.3????????????entry??????
            //4.3.0.1?????????????????????????????????????????????: selectedInfos(???4.3.3.1??????)
            sql= """
                    select course_id "courseId",
                           c.name||'['||s.name||']' "fullName"
                    from mybatis.student_section
                         join mybatis.section s on s.id=section_id
                                       and semester_id=?
                                       and student_id=?
                         join mybatis.course c on c.id=course_id""";
            ArrayList<SelectedInfo> selectedInfos = Util.query(SelectedInfo.class,con,sql,semesterId,studentId);
            //4.3.0.2???????????????????????????????????????classes: enrolledClasses(???4.3.3.2??????)
            String sql6= """
                    select day_of_week "dayOfWeek",
                           class_begin "classBegin",class_end "classEnd",
                           week_list "weekList",
                           c.name||'['||s.name||']' "sectionFullName"
                    from mybatis.student_section
                         join mybatis.section s on section_id=s.id
                                      and student_id=?
                                      and semester_id=?
                         join mybatis.section_class on section_class.section_id=s.id
                         join mybatis.course c on c.id=s.course_id""";
            ArrayList<ClassInfo> enrolledClasses = Util.query(ClassInfo.class,con,sql6,studentId,semesterId);
            for (Integer sectionId : sectionIds) {
                CourseSearchEntry entry = new CourseSearchEntry();
                entry.sectionClasses=new HashSet<>();
                //hasGenerate????????????????????????sectionId?????????course???section??????????????????
                boolean hasGenerate=false;
                for (Info info : infoList) {
                    if(info.sectionId==sectionId){
                        if(!hasGenerate) {
                            //4.3.1??????course
                            sql = """
                                select id,name,credit,class_hour "classHour"
                                from mybatis.course
                                where id=?""";
                            entry.course=Util.query(Course.class,con,sql,info.courseId).get(0);
                            //4.3.2??????section
                            sql="""
                                select id,name,total_capacity "totalCapacity",left_capacity "leftCapacity"
                                from mybatis.section
                                where id=?""";
                            entry.section=Util.query(CourseSection.class,con,sql,sectionId).get(0);
                            //4.3.3??????conflictCourseNames
                            ArrayList<String> conflictCourseNames = new ArrayList<>();
                            //4.3.3.1????????????
                            for (SelectedInfo it : selectedInfos) {
                                if(it.courseId.equals(entry.course.id)){
                                    conflictCourseNames.add(it.fullName);
                                }
                            }
                            //4.3.3.2????????????
                            //4.3.3.2.1?????????sectionId???classes:sectionClasses
                            sql= """
                                    select day_of_week "dayOfWeek",
                                           class_begin "classBegin",class_end "classEnd",
                                           week_list "weekList"
                                    from mybatis.section_class
                                    where section_id=?;""";
                            ArrayList<CourseSectionClass> sectionClasses =
                                    Util.query(CourseSectionClass.class,con,sql,sectionId);
                            //4.3.3.2.2??????sectionClasses???classes,????????????????????????(??????????????????)
                            for (CourseSectionClass sectionClass : sectionClasses) {
                                for (ClassInfo enrolledClass : enrolledClasses) {
                                    //4.3.3.2.2.1??????????????????DayOfWeek && ??????????????????????????????
                                    if(sectionClass.dayOfWeek==enrolledClass.dayOfWeek
                                            && sectionClass.classEnd>=enrolledClass.classBegin
                                            && enrolledClass.classEnd>=sectionClass.classBegin){
                                        //4.3.3.2.2.2?????????weekList????????????
                                        for (Short week : sectionClass.weekList) {
                                            if(enrolledClass.weekList.contains(week)){
                                                String conflictCourseName=enrolledClass.sectionFullName;
                                                if(!conflictCourseNames.contains(conflictCourseName)){
                                                    conflictCourseNames.add(conflictCourseName);
                                                }
                                                break;
                                            }
                                        }
                                    }
                                }
                            }
                            if(!conflictCourseNames.isEmpty()) {
                                Collections.sort(conflictCourseNames);
                                //??????Judge??????
                                int indexI = -1, indexII = -1;
                                for (int i = 0; i < conflictCourseNames.size(); i++) {
                                    if (conflictCourseNames.get(i).contains("????????????I")) {
                                        indexI = i;
                                    }
                                }
                                for (int i = 0; i < conflictCourseNames.size(); i++) {
                                    if (conflictCourseNames.get(i).contains("????????????II")) {
                                        indexII = i;
                                        break;
                                    }
                                }
                                if(indexI!=-1 && indexII!=-1) {
                                    Collections.swap(conflictCourseNames, indexI, indexII);
                                }
                            }
                            entry.conflictCourseNames=conflictCourseNames;
                            hasGenerate=true;
                        }
                        //4.3.4??????sectionClasses
                        CourseSectionClass clazz = new CourseSectionClass();
                        Instructor instructor = new Instructor();
                        instructor.id=info.instructorId;
                        instructor.fullName=info.instructorFullName;
                        clazz.instructor=instructor;
                        clazz.weekList=info.weekList;
                        clazz.id=info.classId;
                        clazz.dayOfWeek=info.dayOfWeek;
                        clazz.classBegin=info.classBegin;
                        clazz.classEnd=info.classEnd;
                        clazz.location=info.location;
                        entry.sectionClasses.add(clazz);
                    }
                }
                entries.add(entry);
            }
            //5.??????ignoreConflict
            Stream<CourseSearchEntry> entryStream = entries.stream();
            if(ignoreConflict){
                entryStream=entryStream.filter(it -> it.conflictCourseNames.isEmpty());
            }
            //6.??????offset???size
            return entryStream.skip(pageIndex*(long)pageSize).limit(pageSize).collect(Collectors.toList());
        } catch (SQLException e) {
            e.printStackTrace();
            System.exit(1);
            return null;
        }
    }

    @Override
    public EnrollResult enrollCourse(int studentId, int sectionId) {
        //2&3&5.1???????????????????????????
        try(Connection con=dataSource.getConnection()){
            //1.???????????????
            String sql1 = "select left_capacity from mybatis.section where id=?";
            ArrayList<Integer> capacity = Util.querySingle(con, sql1, sectionId);
            if(capacity.isEmpty()){
                return EnrollResult.COURSE_NOT_FOUND;
            }
            //2.????????????????????????
            String sql2="select mark from mybatis.student_section where section_id=? and student_id=?";
            if(!Util.querySingle(con, sql2, sectionId, studentId).isEmpty()){
                return EnrollResult.ALREADY_ENROLLED;
            }
            //3.????????????????????????
            String sql3= """
                    select course_id
                    from mybatis.section join mybatis.student_section
                         on id=section_id
                         and student_id=?
                         and mark>=60
                         and course_id = (
                             select course_id
                             from mybatis.section
                             where id=?
                         )""";//?????????????????????????????????courseId?????????????????????????????????
            if(!Util.querySingle(con, sql3, studentId, sectionId).isEmpty()){
                return EnrollResult.ALREADY_PASSED;
            }
            //4.??????????????????
            String sql4="select course_id from mybatis.section where id=?";
            String courseId=(String)Util.querySingle(con,sql4,sectionId).get(0);
            if(!passedPrerequisitesForCourse(studentId,courseId)){
                return EnrollResult.PREREQUISITES_NOT_FULFILLED;
            }
            //5.??????
            //5.1????????????(?????????????????????)
            String sql5= """
                    select course_id
                    from mybatis.section join mybatis.student_section
                         on id=section_id
                         and student_id=?
                         and mark=-1
                         and course_id= (
                             select course_id
                             from mybatis.section
                             where id=?
                         )""";//??????????????????????????????courseId??????????????????????????????????????????
            if(!Util.querySingle(con,sql5,studentId,sectionId).isEmpty()){
                return EnrollResult.COURSE_CONFLICT_FOUND;
            }
            //5.2????????????
            //5.2.1?????????????????????????????????classes: enrolledClasses
            String sql6= """
                    select day_of_week "dayOfWeek",
                           class_begin "classBegin",class_end "classEnd",
                           week_list "weekList"
                    from mybatis.student_section
                         join mybatis.section on section_id=mybatis.section.id
                                      and student_id=?
                                      and semester_id=(
                                              select semester_id
                                              from mybatis.section
                                              where id=?
                                          )
                         join mybatis.section_class on mybatis.section_class.section_id=section.id""";
            ArrayList<CourseSectionClass> enrolledClasses =
                    Util.query(CourseSectionClass.class,con,sql6,studentId,sectionId);
            //5.2.2????????????????????????
            if(timeConflictFound(con,sectionId,enrolledClasses)){return EnrollResult.COURSE_CONFLICT_FOUND;}
            //6.????????????
            if(capacity.get(0)==0){
                return EnrollResult.COURSE_IS_FULL;
            }
            //7.????????????
            //7.1????????????student_section??????
            String sql8="insert into mybatis.student_section values (?,?,-1)";//????????????????????????mark???-1
            Util.update(con,sql8,studentId,sectionId);
            //7.2???section??????left_capacity--
            updateLeftCapacity(con,sectionId,true);
            return EnrollResult.SUCCESS;
        } catch (SQLException e) {
            e.printStackTrace();
            //8.????????????
            return EnrollResult.UNKNOWN_ERROR;
        }
    }

    /**
     * ?????????section????????????????????????"????????????"
     * @param enrolledClasses ????????????????????????class
     */
    private boolean timeConflictFound(Connection con,int sectionId,List<CourseSectionClass> enrolledClasses){
        //1.?????????sectionId???classes:sectionClasses
        String sql= """
                    select day_of_week "dayOfWeek",
                           class_begin "classBegin",class_end "classEnd",
                           week_list "weekList"
                    from mybatis.section_class
                    where section_id=?;""";
        ArrayList<CourseSectionClass> sectionClasses =
                Util.query(CourseSectionClass.class,con,sql,sectionId);
        //2.??????sectionClasses???classes,????????????????????????(??????????????????)
        for (CourseSectionClass sectionClass : sectionClasses) {
            for (CourseSectionClass enrolledClass : enrolledClasses) {
                //2.1??????????????????DayOfWeek && ??????????????????????????????
                if(sectionClass.dayOfWeek==enrolledClass.dayOfWeek
                   && sectionClass.classEnd>=enrolledClass.classBegin
                   && enrolledClass.classEnd>=sectionClass.classBegin){
                    //2.2?????????weekList????????????
                    for (Short week : sectionClass.weekList) {
                        if(enrolledClass.weekList.contains(week)){
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    @Override
    public void dropCourse(int studentId, int sectionId) throws IllegalStateException {
        String sql="select mark from mybatis.student_section where student_id=? and section_id=?";
        try (Connection con=dataSource.getConnection()){
            ArrayList<Integer> res=Util.querySingle(con,sql,studentId,sectionId);
            if(res.isEmpty()){
                throw new EntityNotFoundException();
            }
            if(res.get(0)!=-1){
                throw new IllegalStateException();
            }
            sql="delete from mybatis.student_section where student_id=? and section_id=?";
            Util.update(con,sql,studentId,sectionId);
            updateLeftCapacity(con,sectionId,false);
        } catch (SQLException e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    @Override
    public void addEnrolledCourseWithGrade(int studentId, int sectionId, @Nullable Grade grade) {
        //????????????left_capacity
        try(Connection con=dataSource.getConnection()){
            int mark;
            String sql1= """
                    select distinct s.id
                    from mybatis.section s join mybatis.course c on c.id = s.course_id
                    where s.id=?""";
            PreparedStatement ps1=con.prepareStatement(sql1);
            ps1.setInt(1,sectionId);
            ResultSet rs1=ps1.executeQuery();
            rs1.next();
            boolean isPf=rs1.getBoolean(2);
            if(isPf){
                if(grade==PassOrFailGrade.PASS){
                    mark=-2;
                }else if(grade==PassOrFailGrade.FAIL){
                    mark=-3;
                }else {
                    mark=-1; //??? ??? ???????????????course????????????????????????PF?????????????????????????????????
                }
            }else{
                if(grade==null){
                    mark=-1;
                }else{
                   mark= grade.when(new Grade.Cases<>() {
                       @Override
                       public Integer match(PassOrFailGrade self) {
                           return -1;
                       }//?????????

                       @Override
                       public Integer match(HundredMarkGrade self) {
                           return (int) (self.getMark());
                       }
                   });
                }
            }
            String sql2= """
                    select * from mybatis.student_section
                    where section_id=? and student_id=?;""";
            PreparedStatement ps2=con.prepareStatement(sql2);
            ps2.setInt(1,sectionId);
            ps2.setInt(2,studentId);
//            ResultSet rs=ps2.executeQuery();
//            if(rs.wasNull()){
                String sql="insert into mybatis.student_section(student_id, section_id, mark) values (?,?,?);";
                try{
                    Util.update(con,sql,studentId,sectionId,mark);
                }catch (SQLException e){
                    String sql3="update mybatis.student_section set mark=?\n" +
                        "where student_section.student_id=? and student_section.section_id=?;";
                    Util.update(con,sql3,mark,studentId,sectionId);
                }
//            }else {
//                String sql3="update student_section set mark=?\n" +
//                        "where student_section.student_id=? and student_section.section_id=?;";
//                Util.update(con,sql3,mark,studentId,sectionId);
//            }
        } catch (SQLException throwables) {
            throwables.printStackTrace();
            throw new IntegrityViolationException();
        }
    }

    private synchronized void updateLeftCapacity(Connection con,int sectionId,boolean isAdd) throws SQLException{
        //isAdd???true: left_capacity--
        //isAdd???false: left_capacity++
        String addSql= """
                update mybatis.section
                set left_capacity=(
                        select left_capacity
                        from mybatis.section
                        where id=?
                    )-1
                where id=?""";
        String dropSql= """
                update mybatis.section
                set left_capacity=(
                        select left_capacity
                        from mybatis.section
                        where id=?
                    )+1
                where id=?""";
        PreparedStatement ps;
        if(isAdd){ps=con.prepareStatement(addSql);}
        else{ps=con.prepareStatement(dropSql);}
        ps.setInt(1,sectionId);
        ps.setInt(2,sectionId);
        ps.executeUpdate();
        ps.close();
    }

    @Override
    public void setEnrolledCourseGrade(int studentId, int sectionId, Grade grade) {
        try(Connection con=dataSource.getConnection()) {
            int mark;
            String sql1= """
                    select distinct s.id
                     from mybatis.section s join mybatis.course c on c.id = s.course_id
                     where s.id=?;""";
            PreparedStatement ps1=con.prepareStatement(sql1);
            ps1.setInt(1,sectionId);
            ResultSet rs1=ps1.executeQuery();
            boolean is_pf=rs1.getBoolean(2);
            if(is_pf){
                if(grade==PassOrFailGrade.PASS){
                    mark=-2;
                }else if(grade==PassOrFailGrade.FAIL){
                    mark=-3;
                }else {
                    mark=-1; //??? ??? ???????????????course????????????????????????PF?????????????????????????????????
                }
            }else{
                mark= grade.when(new Grade.Cases<>() {
                    @Override
                    public Integer match(PassOrFailGrade self) {
                        return -1;
                    }//?????????

                    @Override
                    public Integer match(HundredMarkGrade self) {
                        return (int) (self.getMark());
                    }
                });
            }
            String sql="update mybatis.student_section set mark=? where section_id=? and student_id=?;";
            PreparedStatement ps=con.prepareStatement(sql);
            ps.setInt(1,mark);
            ps.setInt(2,sectionId);
            ps.setInt(3,studentId);
            ps.executeUpdate();
        } catch (SQLException throwables) {
            throwables.printStackTrace();
        }
    }

    @Override
    public Map<Course, Grade> getEnrolledCoursesAndGrades(int studentId, @Nullable Integer semesterId) {
        Map<Course,Grade> courseGradeMap=new HashMap<>();
        try(Connection con=dataSource.getConnection()) {
            String sql= """
                    select student_section.section_id,student_section.mark,course.id,course.name,course.credit,course.class_hour from(
                           select section_id,student_id,course_id from
                           ((select section_id,mark,student_id from mybatis.student_section where student_id=?) a
                           join mybatis.section s on s.id=a.section_id) b
                           where b.semester_id=?) c
                           join mybatis.student_section on c.student_id=student_section.student_id and c.section_id=student_section.student_id
                           join mybatis.section s2 on s2.id = student_section.section_id
                           join mybatis.course on course.id=s2.course_id
                           order by s2.semester_id;""";
            /* c????????????????????????????????????all_section??????????????????????????????????????????????????????order by semester_id ?????????????????????
                ??????course ????????????course??????
            */
            PreparedStatement ps = con.prepareStatement(sql);
            ps.setInt(1,studentId);
            ps.setInt(2,semesterId);
            ResultSet rs=ps.executeQuery();
            while(rs.next()){
                Course course=new Course();
                course.id=rs.getString(3);
                course.name=rs.getString(4);
                course.credit=rs.getInt(5);
                boolean is_pf=rs.getBoolean(6);
                if(is_pf){
                    course.grading= Course.CourseGrading.PASS_OR_FAIL;
                }else {
                    course.grading= Course.CourseGrading.HUNDRED_MARK_SCORE;
                }
                int mark=rs.getInt(2);
                Grade grade;
                if(mark==-1){
                    grade=null;
                }else if(mark==-2){
                    grade= PassOrFailGrade.PASS;
                }else if(mark==-3){
                    grade=PassOrFailGrade.FAIL;
                }else{
                    grade=new HundredMarkGrade((short) mark);
                }
                courseGradeMap.put(course,grade);
            }
            return courseGradeMap;
        } catch (SQLException throwables) {
            throwables.printStackTrace();
            throw new EntityNotFoundException();
        }
    }

    @Override
    public CourseTable getCourseTable(int studentId, Date date) {
        //System.out.println(studentId + " " + date);
        CourseTable ct = new CourseTable();
        ct.table=new HashMap<>();
        try(Connection con=dataSource.getConnection()){
            String psql = """
                    select *
                    from mybatis.semester
                    where begin_time <= ? and end_time >= ?
                    """;
            PreparedStatement pps = con.prepareStatement(psql);
            pps.setDate(1,date);
            pps.setDate(2,date);
            ResultSet prs = pps.executeQuery();
            int sid = 0;
            int week = 0;
            while(prs.next()) {
                sid = prs.getInt(1);
                Date ks = prs.getDate(3);
                long days = (date.getTime() - ks.getTime())/(1000*3600*24);
                week = (int) (days / 7 + 1);
            }
            String sql = """
                    select c.name, s.name, sc.instructor_id, i.full_name, sc.class_begin, sc.class_end, location, sc.day_of_week, sc.week_list
                    from mybatis.student
                             join mybatis.student_section ss on student.id = ss.student_id
                             join mybatis.section s on s.id = ss.section_id
                             join mybatis.course c on c.id = s.course_id
                             join mybatis.section_class sc on s.id = sc.section_id
                             join mybatis.instructor i on i.id = sc.instructor_id
                    where student.id = ? and s.semester_id = ?""";
            PreparedStatement ps = con.prepareStatement(sql);
            ps.setInt(1,studentId);
            ps.setInt(2, sid);
            ResultSet rs = ps.executeQuery();
            // ??????????????????????????????????????????????????????
            ArrayList<Set<CourseTable.CourseTableEntry>> ctSet = new ArrayList<>();
            for(int i=0;i<7;i++){
                //12.12???????????????????????????set??????????????????????????????????????????????????????????????????LinkedHashset(?????????????????????????????????)
                Set<CourseTable.CourseTableEntry> set = new HashSet<>();
                ctSet.add(set);
            }
            while(rs.next()) {
                String wl = rs.getString(9);
                String wk = String.valueOf(week);
                if(!wl.contains(wk)) continue;
                String courseName = rs.getString(1);
                String sectionName = rs.getString(2);
                int instructorId = rs.getInt(3);
                String IFullName = rs.getString(4);
                Instructor ins = new Instructor();
                ins.id = instructorId;
                ins.fullName = IFullName;
                short begin = rs.getShort(5);
                short end = rs.getShort(6);
                String location = rs.getString(7);
                CourseTable.CourseTableEntry entry = new CourseTable.CourseTableEntry();
                entry.courseFullName = String.format("%s[%s]", courseName, sectionName);
                entry.classBegin = begin;
                entry.classEnd = end;
                entry.instructor = ins;
                entry.location = location;
                int weekday = rs.getInt(8);
                ctSet.get(weekday - 1).add(entry);
            }
            for(int i=0;i<7;i++){
                DayOfWeek dow = DayOfWeek.of(i+1);
                ct.table.put(dow, ctSet.get(i));
            }
        }catch(SQLException e){
            e.printStackTrace();
        }
        return ct;
    }

    @Override
    public boolean passedPrerequisitesForCourse(int studentId, String courseId) {
        try(Connection con=dataSource.getConnection()) {
            //???????????????????????????id: passedCids
            String sql1= """
                    select distinct course_id
                    from mybatis.section
                    where id in(
                        select section_id
                        from mybatis.student_section
                        where student_id=? and mark>=60
                    )""";
            ArrayList<String> passedCids = Util.querySingle(con, sql1, studentId);
            return passedPre(passedCids,courseId);
        } catch (SQLException e) {
            e.printStackTrace();
            System.exit(1);
            return false;
        }
    }

    /**
     * ??????????????????????????????????????????passedCids???????????????courseId??????????????????
     */
    public boolean passedPre(ArrayList<String> passedCids,String courseId){
        try (Connection con=dataSource.getConnection()){
            //1.?????????????????????
            //1.1???????????????String: pre
            String sql="select prerequisite from mybatis.course where id=?";
            String pre = (String)Util.querySingle(con, sql, courseId).get(0);
            if(pre==null){return true;}
            //1.2?????????pre????????????id: eg:((MA101A OR MA101B) AND MA103A)
            String[] preCids = pre.split(" (AND|OR) ");// ((MA101A MA101B) MA103A)
            for (int i = 0; i < preCids.length; i++) {
                preCids[i]=preCids[i].replaceAll("[()]","");
            }//????????????: MA101A MA101B MA103A
            //1.3???pre?????????????????????
            pre=pre.replace(" AND ","&").replace(" OR ","|");
            for (String preCid : preCids) {
                pre=pre.replace(preCid,passedCids.contains(preCid)?"T":"F");
            }
            //2.?????????????????????pre: ((T|F)&T)
            //2.1?????????????????????pre?????????????????????postfix: TF|T&
            Stack<Character> stack = new Stack<>();
            StringBuilder postfix = new StringBuilder();
            for (char c : pre.toCharArray()) {
                switch (c) {
                    case '(' -> stack.push('(');
                    case ')' -> {
                        char top;
                        while ((top = stack.pop()) != '(') {
                            postfix.append(top);
                        }
                    }
                    case '&' -> {
                        if (!stack.isEmpty() && stack.peek() == '&') {
                            postfix.append(stack.pop());
                        }
                        stack.push('&');
                    }
                    case '|' -> {
                        if (!stack.isEmpty() && stack.peek() == '&') {
                            postfix.append(stack.pop());
                        }
                        if (!stack.isEmpty() && stack.peek() == '|') {
                            postfix.append(stack.pop());
                        }
                        stack.push('|');
                    }
                    default -> postfix.append(c);//?????????T,F
                }
            }
            while (!stack.isEmpty()){
                postfix.append(stack.pop());
            }
            //2.2???????????????????????????postfix: TF|T&
            Stack<Boolean> stack2 = new Stack<>();
            for (char c : postfix.toString().toCharArray()) {
                switch (c){
                    case '|' -> stack2.push(stack2.pop() | stack2.pop());
                    //?????????????????????||??????????????????pop???????????????bug
                    case '&' -> stack2.push(stack2.pop() & stack2.pop());
                    //?????????????????????&&??????????????????pop???????????????bug
                    case 'T' -> stack2.push(true);
                    case 'F' -> stack2.push(false);
                }
            }
            return stack2.pop();
        } catch (SQLException e) {
            e.printStackTrace();
            System.exit(1);
            return false;
        }
    }

    @Override
    public Major getStudentMajor(int studentId) {
        try(Connection con=dataSource.getConnection()){
            String sql = """
                    select m.id, m.name, m.department_id
                    from mybatis.student join mybatis.major m on m.id = student.major_id
                    where student.id = ?""";
            PreparedStatement ps = con.prepareStatement(sql);
            ps.setInt(1, studentId);
            ResultSet rs = ps.executeQuery();
            int mid = rs.getInt(1);
            String name = rs.getString(2);
            int did = rs.getInt(3);
            String sql2 = """
                    select *
                    from mybatis.department
                    where id = ?""";
            PreparedStatement ps2 = con.prepareStatement(sql2);
            ps2.setInt(1, did);
            ResultSet rs2 = ps2.executeQuery();
            String d_name = rs2.getString(2);
            Department dep = new Department();
            dep.id=did;
            dep.name=d_name;
            Major maj = new Major();
            maj.id=mid;
            maj.name=name;
            maj.department=dep;
            return maj;
        }catch(SQLException throwables){
            throwables.printStackTrace();
            throw new EntityNotFoundException();
        }
    }
}
